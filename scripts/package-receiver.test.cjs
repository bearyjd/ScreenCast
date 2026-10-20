// Fork addition by bearyjd: verify deployable receiver packaging at filesystem/Git boundaries.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { packageReceiver } = require('./package-receiver.cjs');
const root = path.resolve(__dirname, '..');
const CAF = 'https://www.gstatic.com/cast/sdk/libs/caf_receiver/v3/cast_receiver_framework.js';

test('receiver HTML uses its adjacent script and absolute HTTPS Google CAF', () => {
  const html = fs.readFileSync(path.join(root, 'receiver/index.html'), 'utf8');
  const scripts = [...html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)].map(match => match[1]);
  assert.deepEqual(scripts, [CAF, './receiver.js']);
});

function git(source, ...args) {
  return execFileSync('git', args, { cwd: source, encoding: 'utf8',
    env: { ...process.env, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null' },
    stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}
function fixture() {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'screencast-receiver-test-'));
  const source = path.join(base, 'source');
  fs.mkdirSync(source);
  for (const name of ['receiver/index.html', 'receiver/receiver.js', 'LICENSE', 'receiver/ATTRIBUTION.txt', 'scripts/package-receiver.cjs']) {
    fs.mkdirSync(path.dirname(path.join(source, name)), { recursive: true });
    fs.copyFileSync(path.join(root, name), path.join(source, name));
  }
  fs.writeFileSync(path.join(source, 'private.log'), 'never ship logs or tokens');
  fs.writeFileSync(path.join(source, 'receiver/receiver.test.cjs'), 'never ship tests');
  git(source, 'init', '--quiet');
  git(source, 'add', '.');
  git(source, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
    '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'receiver fixture');
  fs.writeFileSync(path.join(source, 'untracked-secret.env'), 'never ship untracked data');
  return { source, outputDir: path.join(base, 'artifact'), sourceCommit: git(source, 'rev-parse', 'HEAD') };
}
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

test('package contains only exact receiver bytes, attribution, license and committed hash provenance', () => {
  const f = fixture();
  packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit });
  assert.equal(fs.existsSync(f.outputDir), true, 'build must create the requested artifact');
  assert.deepEqual(fs.readdirSync(f.outputDir).sort(), ['ATTRIBUTION.txt', 'LICENSE', 'build-info.json', 'index.html', 'receiver.js']);
  const files = { 'index.html': 'receiver/index.html', 'receiver.js': 'receiver/receiver.js',
    LICENSE: 'LICENSE', 'ATTRIBUTION.txt': 'receiver/ATTRIBUTION.txt' };
  const expectedHashes = {};
  for (const [output, input] of Object.entries(files)) {
    const bytes = fs.readFileSync(path.join(f.source, input));
    assert.deepEqual(fs.readFileSync(path.join(f.outputDir, output)), bytes);
    expectedHashes[output] = sha256(bytes);
  }
  const manifest = JSON.parse(fs.readFileSync(path.join(f.outputDir, 'build-info.json'), 'utf8'));
  assert.deepEqual(manifest, {
    schemaVersion: 1, sourceCommit: f.sourceCommit, repository: 'https://github.com/bearyjd/ScreenCast',
    upstream: { repository: 'https://github.com/ddagunts/ScreenCast', baseCommit: '7e66bbe7ae5cc64c012bbe4987940be67925d137' },
    externalScripts: [CAF], files: expectedHashes,
  });
  assert.match(manifest.sourceCommit, /^[0-9a-f]{40}$/);
});

test('dirty relevant bytes, including staged edits, cannot claim a source commit', () => {
  const f = fixture();
  fs.appendFileSync(path.join(f.source, 'receiver/receiver.js'), '\n// uncommitted token');
  assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit }), /committed|dirty/i);
  assert.equal(fs.existsSync(f.outputDir), false);
  git(f.source, 'add', 'receiver/receiver.js');
  assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit }), /committed|dirty/i);
});

test('a supplied CI source commit must be full-length and match Git HEAD', () => {
  const f = fixture();
  for (const sourceCommit of ['not-a-commit', '0'.repeat(40)]) {
    assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit }), /source commit/i);
    assert.equal(fs.existsSync(f.outputDir), false);
  }
});

test('unavailable Git metadata never produces claimed provenance', () => {
  const f = fixture();
  fs.renameSync(path.join(f.source, '.git'), path.join(path.dirname(f.source), 'git-metadata'));
  assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit }));
  assert.equal(fs.existsSync(f.outputDir), false);
});

test('output cannot target the source tree, including a symlinked parent alias', () => {
  const f = fixture();
  assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: path.join(f.source, 'dist'), sourceCommit: f.sourceCommit }), /output|source/i);
  assert.equal(fs.existsSync(path.join(f.source, 'dist')), false);
  const alias = path.join(path.dirname(f.source), 'alias');
  fs.symlinkSync(f.source, alias, 'dir');
  assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: path.join(alias, 'dist'), sourceCommit: f.sourceCommit }), /output|source/i);
  assert.equal(fs.existsSync(path.join(f.source, 'dist')), false);
});

test('new or empty external output is supported but nonempty directories are preserved', () => {
  const f = fixture();
  fs.mkdirSync(f.outputDir);
  packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit });
  const before = fs.readFileSync(path.join(f.outputDir, 'index.html'));
  fs.writeFileSync(path.join(f.outputDir, 'private.txt'), 'preserve me');
  assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit }));
  assert.deepEqual(fs.readFileSync(path.join(f.outputDir, 'index.html')), before);
  assert.equal(fs.readFileSync(path.join(f.outputDir, 'private.txt'), 'utf8'), 'preserve me');
});

test('symlinked input files and input directories are rejected even for matching bytes', () => {
  for (const directory of [false, true]) {
    const f = fixture();
    const input = path.join(f.source, directory ? 'receiver' : 'receiver/receiver.js');
    const target = path.join(path.dirname(f.source), directory ? 'borrowed-receiver' : 'borrowed.js');
    fs.renameSync(input, target);
    fs.symlinkSync(target, input, directory ? 'dir' : 'file');
    assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit }), /symlink/i);
    assert.equal(fs.existsSync(f.outputDir), false);
  }
});

test('committed HTML cannot reference unshipped local scripts or an unexpected CAF URL', () => {
  for (const [before, after] of [['./receiver.js', './missing.js'], [CAF, CAF.replace('https:', 'http:')], [CAF, CAF.replace('https:', '')]]) {
    const f = fixture();
    const html = path.join(f.source, 'receiver/index.html');
    fs.writeFileSync(html, fs.readFileSync(html, 'utf8').replace(before, after));
    git(f.source, 'add', 'receiver/index.html');
    git(f.source, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
      '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'asset fixture');
    f.sourceCommit = git(f.source, 'rev-parse', 'HEAD');
    assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit }), /asset|CAF|script/i);
    assert.equal(fs.existsSync(f.outputDir), false);
  }
});

test('identical committed input produces byte-identical output in another directory', () => {
  const f = fixture();
  const second = path.join(path.dirname(f.outputDir), 'second-artifact');
  for (const outputDir of [f.outputDir, second]) packageReceiver({ sourceRoot: f.source, outputDir, sourceCommit: f.sourceCommit });
  for (const file of fs.readdirSync(f.outputDir)) assert.deepEqual(fs.readFileSync(path.join(f.outputDir, file)), fs.readFileSync(path.join(second, file)));
});

test('CLI packages its clean checkout with CI commit validation', () => {
  const f = fixture();
  execFileSync(process.execPath, [path.join(f.source, 'scripts/package-receiver.cjs'), f.outputDir],
    { env: { ...process.env, GITHUB_SHA: f.sourceCommit }, stdio: ['ignore', 'pipe', 'pipe'] });
  assert.equal(fs.existsSync(path.join(f.outputDir, 'index.html')), true);
  assert.equal(JSON.parse(fs.readFileSync(path.join(f.outputDir, 'build-info.json'), 'utf8')).sourceCommit, f.sourceCommit);
});

test('index-only staged divergence is rejected even when working bytes equal HEAD', () => {
  const f = fixture();
  const input = path.join(f.source, 'receiver/receiver.js');
  const committed = fs.readFileSync(input);
  fs.appendFileSync(input, '\n// staged change');
  git(f.source, 'add', 'receiver/receiver.js');
  fs.writeFileSync(input, committed);
  assert.throws(() => packageReceiver({ sourceRoot: f.source, outputDir: f.outputDir, sourceCommit: f.sourceCommit }), /staged/i);
  assert.equal(fs.existsSync(f.outputDir), false);
});
