// Fork addition by bearyjd: prepare a receiver-only static artifact without deployment.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const CAF = 'https://www.gstatic.com/cast/sdk/libs/caf_receiver/v3/cast_receiver_framework.js';
const inputs = {
  'index.html': 'receiver/index.html', 'receiver.js': 'receiver/receiver.js',
  LICENSE: 'LICENSE', 'ATTRIBUTION.txt': 'receiver/ATTRIBUTION.txt',
};
function git(sourceRoot, args) {
  try {
    return execFileSync('git', args, { cwd: sourceRoot, stdio: ['ignore', 'pipe', 'pipe'] });
  } catch {
    throw new Error('Source requires available Git metadata and committed relevant inputs');
  }
}
function packageReceiver({ sourceRoot = path.resolve(__dirname, '..'), outputDir, sourceCommit: expectedCommit = process.env.GITHUB_SHA }) {
  const sourceCommit = git(sourceRoot, ['rev-parse', 'HEAD']).toString().trim();
  if (!/^[0-9a-f]{40}$/.test(sourceCommit) || (expectedCommit !== undefined && expectedCommit !== sourceCommit)) {
    throw new Error('Supplied source commit must be full-length and match Git HEAD');
  }
  const relevant = [...Object.values(inputs), 'scripts/package-receiver.cjs'];
  try {
    git(sourceRoot, ['diff', '--cached', '--quiet', sourceCommit, '--', ...relevant]);
  } catch {
    throw new Error('Relevant staged inputs differ from committed source bytes');
  }
  const committed = {};
  for (const input of relevant) {
    let current = sourceRoot;
    for (const component of input.split('/')) {
      current = path.join(current, component);
      if (fs.lstatSync(current).isSymbolicLink()) throw new Error('Symlink inputs are not supported');
    }
    if (!fs.lstatSync(current).isFile() || !/^100(?:644|755) blob [0-9a-f]{40}\t/.test(git(sourceRoot, ['ls-tree', sourceCommit, '--', input]).toString())) {
      throw new Error('Relevant input must be a committed regular file');
    }
    const bytes = git(sourceRoot, ['show', `${sourceCommit}:${input}`]);
    if (!bytes.equals(fs.readFileSync(path.join(sourceRoot, input)))) throw new Error('Relevant input differs from committed bytes');
    committed[input] = bytes;
  }
  const references = [...committed['receiver/index.html'].toString('utf8').matchAll(/\b(?:src|href)\s*=\s*(["'])(.*?)\1/g)].map(match => match[2]);
  if (references.length !== 2 || references[0] !== CAF || references[1] !== './receiver.js') {
    throw new Error('HTML assets must be adjacent receiver.js and absolute HTTPS Google CAF');
  }
  if (typeof outputDir !== 'string' || !path.isAbsolute(outputDir)) throw new Error('Output directory must be an absolute path');
  const source = fs.realpathSync(sourceRoot);
  const output = path.join(fs.realpathSync(path.dirname(outputDir)), path.basename(outputDir));
  const within = (parent, child) => {
    const relative = path.relative(parent, child);
    return relative === '' || (relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative));
  };
  if (within(source, output) || within(output, source)) throw new Error('Output must be outside the source tree and its ancestors');
  let existing;
  try { existing = fs.lstatSync(output); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (existing && (!existing.isDirectory() || existing.isSymbolicLink() || fs.readdirSync(output).length !== 0)) {
    throw new Error('Output must be a new or empty directory, never a symlink');
  }
  const files = {};
  if (!existing) fs.mkdirSync(output);
  outputDir = output;
  for (const [output, input] of Object.entries(inputs)) {
    const bytes = committed[input];
    fs.writeFileSync(path.join(outputDir, output), bytes, { flag: 'wx' });
    files[output] = crypto.createHash('sha256').update(bytes).digest('hex');
  }
  const manifest = {
    schemaVersion: 1, sourceCommit, repository: 'https://github.com/bearyjd/ScreenCast',
    upstream: { repository: 'https://github.com/ddagunts/ScreenCast', baseCommit: '7e66bbe7ae5cc64c012bbe4987940be67925d137' },
    externalScripts: [CAF], files,
  };
  fs.writeFileSync(path.join(outputDir, 'build-info.json'), JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx' });
  return manifest;
}
module.exports = { packageReceiver };

if (require.main === module) {
  try {
    if (process.argv.length !== 3) throw new Error('Usage: node scripts/package-receiver.cjs /absolute/empty-output-directory');
    packageReceiver({ outputDir: process.argv[2] });
  } catch (error) {
    process.stderr.write(`Receiver package failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
