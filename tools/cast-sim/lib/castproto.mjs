// Hand-rolled protobuf codec for the Cast V2 CastMessage envelope plus the
// 4-byte big-endian length framing. Field numbers mirror cast_channel.proto:
//   1 protocol_version (varint, 0 = CASTV2_1_0)   2 source_id (string)
//   3 destination_id (string)   4 namespace (string)
//   5 payload_type (varint, 0 STRING / 1 BINARY)
//   6 payload_utf8 (string)     7 payload_binary (bytes)

export const PAYLOAD_STRING = 0;
export const PAYLOAD_BINARY = 1;
// The Android sender (CastChannel.kt) rejects frames longer than this.
export const MAX_FRAME = 65536;

function writeVarint(out, value) {
  let v = BigInt(value);
  while (v > 0x7fn) {
    out.push(Number((v & 0x7fn) | 0x80n));
    v >>= 7n;
  }
  out.push(Number(v));
}

function writeBytes(out, field, bytes) {
  writeVarint(out, (field << 3) | 2);
  writeVarint(out, bytes.length);
  for (const b of bytes) out.push(b);
}

export function encodeCastMessage(msg) {
  const out = [];
  writeVarint(out, 1 << 3); writeVarint(out, msg.protocolVersion ?? 0);
  writeBytes(out, 2, Buffer.from(msg.sourceId, 'utf8'));
  writeBytes(out, 3, Buffer.from(msg.destinationId, 'utf8'));
  writeBytes(out, 4, Buffer.from(msg.namespace, 'utf8'));
  const binary = msg.payloadBinary != null;
  writeVarint(out, 5 << 3); writeVarint(out, binary ? PAYLOAD_BINARY : PAYLOAD_STRING);
  if (binary) writeBytes(out, 7, msg.payloadBinary);
  else writeBytes(out, 6, Buffer.from(msg.payloadUtf8 ?? '', 'utf8'));
  return Buffer.from(out);
}

function readVarint(buf, start) {
  let result = 0n;
  let shift = 0n;
  const limit = Math.min(buf.length, start + 10);
  for (let i = start; i < limit; i++) {
    const b = buf[i];
    result |= BigInt(b & 0x7f) << shift;
    if ((b & 0x80) === 0) return [Number(result), i + 1];
    shift += 7n;
  }
  throw new Error(`malformed varint at offset ${start}`);
}

export function decodeCastMessage(buf) {
  const msg = {
    protocolVersion: 0, sourceId: '', destinationId: '', namespace: '',
    payloadType: PAYLOAD_STRING, payloadUtf8: '', payloadBinary: null,
  };
  let off = 0;
  while (off < buf.length) {
    let tag;
    [tag, off] = readVarint(buf, off);
    const field = tag >>> 3;
    const wire = tag & 7;
    if (wire === 0) {
      let v;
      [v, off] = readVarint(buf, off);
      if (field === 1) msg.protocolVersion = v;
      else if (field === 5) msg.payloadType = v;
    } else if (wire === 1) {
      if (off + 8 > buf.length) throw new Error('fixed64 past end');
      off += 8;
    } else if (wire === 5) {
      if (off + 4 > buf.length) throw new Error('fixed32 past end');
      off += 4;
    } else if (wire === 2) {
      let len;
      [len, off] = readVarint(buf, off);
      if (off + len > buf.length) throw new Error(`len-delimited past end (${len})`);
      const slice = buf.subarray(off, off + len);
      if (field === 2) msg.sourceId = slice.toString('utf8');
      else if (field === 3) msg.destinationId = slice.toString('utf8');
      else if (field === 4) msg.namespace = slice.toString('utf8');
      else if (field === 6) msg.payloadUtf8 = slice.toString('utf8');
      else if (field === 7) msg.payloadBinary = Buffer.from(slice);
      off += len;
    } else {
      throw new Error(`unsupported wire type ${wire} at field ${field}`);
    }
  }
  return msg;
}

export function frame(msg) {
  const body = encodeCastMessage(msg);
  const head = Buffer.alloc(4);
  head.writeUInt32BE(body.length);
  return Buffer.concat([head, body]);
}

// Incremental de-framer: push(chunk) returns decoded messages; throws on a
// frame length of 0 or above MAX_FRAME (same limits as the Android sender).
export class FrameReader {
  #buf = Buffer.alloc(0);
  push(chunk) {
    this.#buf = this.#buf.length ? Buffer.concat([this.#buf, chunk]) : chunk;
    const out = [];
    while (this.#buf.length >= 4) {
      const len = this.#buf.readUInt32BE(0);
      if (len === 0 || len > MAX_FRAME) throw new Error(`bad frame length ${len}`);
      if (this.#buf.length < 4 + len) break;
      out.push(decodeCastMessage(this.#buf.subarray(4, 4 + len)));
      this.#buf = this.#buf.subarray(4 + len);
    }
    return out;
  }
}
