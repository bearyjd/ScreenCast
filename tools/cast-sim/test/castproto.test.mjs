import test from 'node:test';
import assert from 'node:assert/strict';
import { encodeCastMessage, decodeCastMessage, frame, FrameReader, MAX_FRAME, PAYLOAD_BINARY } from '../lib/castproto.mjs';

test('golden bytes: minimal message', () => {
  const bytes = encodeCastMessage({ sourceId: 'a', destinationId: 'b', namespace: 'n', payloadUtf8: '{}' });
  assert.equal(bytes.toString('hex'), '0800' + '120161' + '1a0162' + '22016e' + '2800' + '32027b7d');
});

test('golden bytes: CONNECT as the Android sender encodes it', () => {
  const bytes = encodeCastMessage({
    sourceId: 'sender-0', destinationId: 'receiver-0', namespace: 'urn:x-cast:com.google.cast.tp.connection',
    payloadUtf8: '{"type":"CONNECT"}',
  });
  const expected = Buffer.concat([
    Buffer.from('0800', 'hex'),
    Buffer.from('1208', 'hex'), Buffer.from('sender-0'),
    Buffer.from('1a0a', 'hex'), Buffer.from('receiver-0'),
    Buffer.from('2228', 'hex'), Buffer.from('urn:x-cast:com.google.cast.tp.connection'),
    Buffer.from('2800', 'hex'),
    Buffer.from('3212', 'hex'), Buffer.from('{"type":"CONNECT"}'),
  ]);
  assert.deepEqual(bytes, expected);
});

test('multi-byte varint length (payload of 200 bytes)', () => {
  const payload = 'x'.repeat(200);
  const bytes = encodeCastMessage({ sourceId: 'a', destinationId: 'b', namespace: 'n', payloadUtf8: payload });
  assert.equal(bytes.subarray(13, 16).toString('hex'), '32c801');
  assert.equal(decodeCastMessage(bytes).payloadUtf8, payload);
});

test('round trip incl. unicode and BINARY payload', () => {
  const m = decodeCastMessage(encodeCastMessage({ sourceId: 's', destinationId: 'd', namespace: 'n', payloadUtf8: 'héllo ✓' }));
  assert.equal(m.payloadUtf8, 'héllo ✓');
  const b = decodeCastMessage(encodeCastMessage({ sourceId: 's', destinationId: 'd', namespace: 'n', payloadBinary: Buffer.from([1, 2, 255]) }));
  assert.equal(b.payloadType, PAYLOAD_BINARY);
  assert.deepEqual([...b.payloadBinary], [1, 2, 255]);
});

test('decoder skips unknown fields and rejects malformed input', () => {
  const base = encodeCastMessage({ sourceId: 'a', destinationId: 'b', namespace: 'n', payloadUtf8: '{}' });
  const withUnknown = Buffer.concat([base, Buffer.from('f80105', 'hex')]); // field 31 varint 5
  assert.equal(decodeCastMessage(withUnknown).namespace, 'n');
  assert.throws(() => decodeCastMessage(Buffer.from('1209616', 'hex').subarray(0, 3)));
  assert.throws(() => decodeCastMessage(Buffer.from('12ff', 'hex')));
  assert.throws(() => decodeCastMessage(Buffer.from('0b', 'hex'))); // group wire type
});

test('FrameReader reassembles split frames and rejects bad lengths', () => {
  const m1 = { sourceId: 'a', destinationId: 'b', namespace: 'n', payloadUtf8: '1' };
  const m2 = { sourceId: 'a', destinationId: 'b', namespace: 'n', payloadUtf8: '2' };
  const wire = Buffer.concat([frame(m1), frame(m2)]);
  const r = new FrameReader();
  const out = [];
  for (let i = 0; i < wire.length; i += 3) out.push(...r.push(wire.subarray(i, i + 3)));
  assert.deepEqual(out.map(m => m.payloadUtf8), ['1', '2']);
  assert.throws(() => new FrameReader().push(Buffer.from([0, 0, 0, 0])));
  const big = Buffer.alloc(4); big.writeUInt32BE(MAX_FRAME + 1);
  assert.throws(() => new FrameReader().push(big));
});
