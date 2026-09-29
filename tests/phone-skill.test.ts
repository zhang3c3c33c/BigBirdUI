import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { applyPhonePrompt, createPhoneSkillRead, PHONE_SKILL_ROOT } from '../pi/phone-skill.ts';
import { buildSystemPrompt, normalizeBuildSystemPromptOptions } from '../node_modules/@earendil-works/pi-coding-agent/dist/core/system-prompt.js';
import { loadSkills } from '@earendil-works/pi-coding-agent';

test('native skill discovery and structured prompt preserve memory in either hook order', () => {
  const loaded = loadSkills({ cwd: process.cwd(), agentDir: path.join(tmpdir(), 'bbui-nonexistent-skills'), skillPaths: [PHONE_SKILL_ROOT], includeDefaults: false });
  assert.equal(loaded.skills.length, 1);
  assert.equal(loaded.skills[0].name, 'phone-operation');
  for (const memoryFirst of [true, false]) {
    const options = normalizeBuildSystemPromptOptions({ cwd: process.cwd(), selectedTools: ['phone_action', 'read'], skills: loaded.skills });
    const memory = () => { options.sections.user_memory = '用户偏好：少糖'; };
    if (memoryFirst) memory();
    applyPhonePrompt(options, '仅virtual；无节点', '任务记录按需保存', '当前环境：fixture');
    if (!memoryFirst) memory();
    const prompt = buildSystemPrompt(options);
    assert.match(prompt, /BBUI手机助手/);
    assert.match(prompt, /<skills>[\s\S]*phone-operation/);
    assert.match(prompt, /用户偏好：少糖/);
    assert.match(prompt, /当前环境：fixture/);
    assert.doesNotMatch(prompt, /expert coding assistant|Main documentation:/);
    assert.equal(options.forceSystemPrompt, undefined);
    options.selectedTools = ['phone_action'];
    assert.doesNotMatch(buildSystemPrompt(options), /<skills>/);
  }
});

test('reader reuses native pagination and rejects private files, traversal and directories', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'bbui-skill-read-'));
  try {
    const skill = path.join(root, 'skill');
    await mkdir(path.join(skill, 'references'), { recursive: true });
    await writeFile(path.join(skill, 'SKILL.md'), '一\n二\n三\n四\n');
    await writeFile(path.join(skill, 'references/examples.md'), '案例');
    await writeFile(path.join(root, 'private.md'), 'PRIVATE');
    await writeFile(path.join(skill, 'other.md'), 'NOT ALLOWED');
    const tool = createPhoneSkillRead(skill);
    assert.equal(tool.name, 'read');
    const execute = (file: string, offset?: number, limit?: number, signal?: AbortSignal) => tool.execute('id', { path: file, offset, limit }, signal, undefined, { cwd: skill } as never);
    const result = await execute('SKILL.md', 2, 2);
    assert.match(JSON.stringify(result.content), /二\\n三/);
    assert.equal(result.details.bbuiTool.status, 'complete');
    assert.doesNotMatch(JSON.stringify(result.details), /SKILL.md|一|二|三/);
    assert.match(JSON.stringify((await execute(path.join(skill, 'references/examples.md'))).content), /案例/);
    for (const file of ['../private.md', 'other.md', 'references', path.join(root, 'private.md')]) {
      await assert.rejects(execute(file), error => {
        assert.match(String(error), /指南读取失败/);
        assert.doesNotMatch(String(error), /PRIVATE|NOT ALLOWED|private.md/);
        return true;
      });
    }
    const abort = new AbortController(); abort.abort();
    await assert.rejects(execute('SKILL.md', undefined, undefined, abort.signal));
    const lateAbort = new AbortController();
    const pending = execute('SKILL.md', undefined, undefined, lateAbort.signal);
    lateAbort.abort();
    await assert.rejects(pending);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('canonical allowlist rejects a skill document symlink escaping its packaged root', async t => {
  const root = await mkdtemp(path.join(tmpdir(), 'bbui-skill-link-'));
  try {
    const skill = path.join(root, 'skill'); await mkdir(skill);
    await writeFile(path.join(root, 'secret.md'), 'SECRET');
    try { await symlink(path.join(root, 'secret.md'), path.join(skill, 'SKILL.md')); }
    catch { t.skip('Host does not permit symlink creation'); return; }
    await assert.rejects(createPhoneSkillRead(skill).execute('id', { path: path.join(skill, 'SKILL.md') }, undefined, undefined, { cwd: skill } as never), /指南读取失败/);
  } finally { await rm(root, { recursive: true, force: true }); }
});
