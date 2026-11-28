import { test, expect } from '@playwright/test';
import { createSkillsMock, SKILL_BODY } from './support/skillsMock';

// 固定相对时间（「1 天前」「12:00」），避免断言随日历漂移。
const FIXED_NOW = new Date('2026-11-25T12:00:00');

async function setup(page, options = {}) {
  const mock = createSkillsMock(options);
  await mock.install(page);
  await page.clock.setFixedTime(FIXED_NOW);
  return mock;
}

const row = (page, id) => page.getByRole('row', { name: new RegExp(id) });

const dataBrowser = (page) =>
  page.locator('.data-browser').filter({ has: page.getByRole('tree', { name: 'data/ 目录树' }) });

test.describe('技能列表', () => {
  test('统计行、需处理行与行菜单的危险动作门禁', async ({ page }) => {
    await setup(page);
    await page.goto('/platform/skills');
    await expect(page.getByRole('heading', { name: '技能' })).toBeVisible();
    await page.waitForLoadState('networkidle');

    const statline = page.locator('.skills-statline');
    await expect(statline).toContainText('2 个技能');
    await expect(statline).toContainText('1 可用');
    await expect(statline).toContainText('1 需处理');
    await expect(statline).toContainText('1 未绑定');

    const poetry = row(page, 'poetry-skills');
    await expect(poetry).toContainText('数据就绪');
    await expect(poetry).toContainText('1 个');
    await expect(poetry.getByRole('button', { name: '打开' })).toBeVisible();

    const policy = row(page, 'payroll-policy-notes');
    await expect(policy).toHaveClass(/row-attention/);
    await expect(policy.getByRole('button', { name: '查看原因' })).toBeVisible();

    await page.getByRole('button', { name: 'poetry-skills 的更多操作' }).click();
    let menu = page.getByRole('menu');
    const poetryDelete = menu.getByRole('menuitem', { name: '删除' });
    await expect(poetryDelete).toBeDisabled();
    await expect(poetryDelete).toHaveAttribute('title', '被 1 个智能体引用，需先解绑');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('menu')).toHaveCount(0);

    await page.getByRole('button', { name: 'payroll-policy-notes 的更多操作' }).click();
    menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: '删除' })).toBeEnabled();
    await menu.getByRole('menuitem', { name: '编辑' }).click();
    await expect(page).toHaveURL(/\/platform\/skills\/payroll-policy-notes\/edit$/);
  });

  test('搜索、类型筛选、数据筛选与排序写入 URL 并驱动结果', async ({ page }) => {
    await setup(page);
    await page.goto('/platform/skills');
    await expect(row(page, 'poetry-skills')).toBeVisible();

    const search = page.getByRole('searchbox', { name: '搜索技能' });
    await search.fill('policy');
    await expect(page).toHaveURL(/keyword=policy/);
    await expect(row(page, 'payroll-policy-notes')).toBeVisible();
    await expect(page.getByRole('row', { name: /poetry-skills/ })).toHaveCount(0);

    await search.fill('');
    await expect(page).not.toHaveURL(/keyword=/);
    await expect(row(page, 'poetry-skills')).toBeVisible();

    await page.getByRole('button', { name: /^1 需处理/ }).click();
    await expect(page).toHaveURL(/view=attention/);
    await expect(page.getByRole('row', { name: /payroll-policy-notes/ })).toHaveCount(1);
    await expect(page.getByRole('row', { name: /poetry-skills/ })).toHaveCount(0);

    await page.getByRole('button', { name: /^全部/ }).first().click();
    await expect(page).not.toHaveURL(/view=/);

    await page.getByLabel('数据状态').selectOption('ready');
    await expect(page).toHaveURL(/dataStatus=ready/);
    await expect(page.getByRole('row', { name: /poetry-skills/ })).toHaveCount(1);
    await expect(page.getByRole('row', { name: /payroll-policy-notes/ })).toHaveCount(0);

    await page.getByLabel('数据状态').selectOption('all');
    await page.getByLabel('排序').selectOption('id');
    await expect(page).toHaveURL(/sort=id/);
    await expect(page.locator('tbody tr').first()).toContainText('payroll-policy-notes');

    await page.locator('.page-head h2').click();
    await page.keyboard.press('/');
    await expect(search).toBeFocused();
  });

  test('列设置写入 localStorage 并在刷新后保留', async ({ page }) => {
    await setup(page);
    await page.goto('/platform/skills');
    await page.getByRole('button', { name: '列设置' }).click();
    const dialog = page.getByRole('dialog', { name: '列设置' });
    await dialog.getByRole('checkbox', { name: '来源' }).check();
    await dialog.getByRole('checkbox', { name: '文件数' }).check();

    await expect(page.getByRole('columnheader', { name: '来源' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '文件数' })).toBeVisible();

    await page.reload();
    await expect(page.getByRole('columnheader', { name: '来源' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '文件数' })).toBeVisible();
  });
});

test.describe('技能工作台', () => {
  test('六页签、右栏标识与概览健康/下一步/时间线', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/poetry-skills');
    await expect(page.getByRole('heading', { name: 'poetry-skills' })).toBeVisible();

    const meta = page.locator('.wb-meta');
    await expect(meta).toContainText('来源 db');
    await expect(meta).toContainText('v7');
    await expect(meta).toContainText('脚本已开启');

    const overviewCard = page.locator('.skill-health-grid');
    await expect(overviewCard).toContainText('1.0 MB');
    await expect(overviewCard).toContainText('/ 2048.0 MB');
    await expect(overviewCard).toContainText('1 个脚本');
    await expect(overviewCard).toContainText('setup：bash scripts/setup.sh');
    await expect(page.getByText('完成 4 / 4')).toBeVisible();
    await expect(page.locator('.timeline')).toContainText('补规则');
    await expect(page.locator('.timeline')).toContainText('当前');

    const rail = page.getByRole('complementary', { name: '技能标识与危险区' });
    await expect(rail).toContainText('db · 可写');
    await expect(rail).toContainText('a1b2c3d4');
    await expect(rail.getByRole('button', { name: '删除技能' })).toBeDisabled();

    await page.getByRole('tab', { name: '正文' }).click();
    await expect(page).toHaveURL(/\/platform\/skills\/poetry-skills\/body$/);
    await expect(page.locator('.skill-body')).toContainText('## 角色');

    await page.getByRole('tab', { name: /^文件/ }).click();
    await expect(page).toHaveURL(/\/platform\/skills\/poetry-skills\/files$/);
    await expect(page.getByRole('navigation', { name: '技能文件' }).locator('.file-node')).toHaveCount(2);
    await expect(page.locator('.file-preview[aria-label="references/rules.md"]')).toBeVisible();

    await page.getByRole('tab', { name: '数据' }).click();
    await expect(page.locator('.data-status')).toContainText('数据就绪');

    await page.getByRole('tab', { name: /^版本/ }).click();
    await expect(page.getByText('5 个版本')).toBeVisible();
    await expect(page.locator('.version-row')).toHaveCount(5);

    await page.locator('.version-row').nth(1).getByRole('button', { name: '回滚' }).click();
    const rollbackDialog = page.getByRole('dialog', { name: '回滚到 v6' });
    await rollbackDialog.getByRole('button', { name: '回滚到 v6' }).click();
    await expect(page.locator('.toast', { hasText: '已回滚到 v6' })).toBeVisible();
    expect(mock.calls.rollbacks[0]).toMatchObject({ id: 'poetry-skills', versionNo: 6, revision: 'rev-7' });

    await page.getByRole('tab', { name: /^绑定/ }).click();
    await expect(page.locator('.binding-name')).toContainText('诗词助手');
  });

  test('数据页签只读浏览：目录树、文本预览与二进制元数据', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/poetry-skills');
    await page.getByRole('tab', { name: '数据' }).click();

    const browser = dataBrowser(page);
    await expect(browser).toContainText('只读');
    const tree = browser.getByRole('tree', { name: 'data/ 目录树' });
    await expect(tree.locator('.tree-row')).toHaveCount(3);
    await expect(tree).toContainText('poets');

    await tree.getByRole('treeitem', { name: /poets/ }).click();
    await expect(tree.getByRole('treeitem', { name: /li-bai\.md/ })).toBeVisible();
    expect(mock.calls.dataTree.map((call) => call.path)).toEqual(['', 'poets']);

    await tree.getByRole('treeitem', { name: /index\.json/ }).click();
    await expect(browser.locator('.preview-pre')).toContainText('"poets"');
    expect(mock.calls.dataFile[0]).toMatchObject({ id: 'poetry-skills', path: 'index.json' });

    await tree.getByRole('treeitem', { name: /cover\.png/ }).click();
    await expect(browser).toContainText('二进制');
    await expect(browser).toContainText('二进制文件不展示内容');
    await expect(browser.locator('.preview-pre')).toHaveCount(0);
  });

  test('数据为空与沙箱忙时分别给出空态与 409 提示', async ({ page }) => {
    await setup(page, { dataEmpty: true });
    await page.goto('/platform/skills/poetry-skills');
    await page.getByRole('tab', { name: '数据' }).click();
    await expect(dataBrowser(page)).toContainText('目录为空');

    await page.unrouteAll({ behavior: 'ignoreErrors' });
    await setup(page, { dataTreeBusy: true });
    await page.goto('/platform/skills/poetry-skills');
    await page.getByRole('tab', { name: '数据' }).click();
    await expect(dataBrowser(page).locator('.browser-error')).toContainText(
      '技能数据正在初始化，请稍后重试'
    );
    await expect(dataBrowser(page).locator('.tree-list')).toHaveCount(0);
  });

  test('data/ 上传写入同一目录（含同名冲突）并可删除', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/poetry-skills');
    await page.getByRole('tab', { name: '数据' }).click();

    const browser = dataBrowser(page);
    await expect(browser).toContainText('运行期只读');
    await expect(browser).toContainText('重建数据会清空此目录');
    const tree = browser.getByRole('tree', { name: 'data/ 目录树' });
    await expect(tree.locator('.tree-row')).toHaveCount(3);
    await expect(browser).toContainText('data/');

    const hiddenInput = browser.locator('.hidden-input');
    await hiddenInput.setInputFiles({
      name: 'manual.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from('x,y\n1,2\n'),
    });
    await expect(browser.getByRole('status')).toContainText('已上传 1 个文件到 data/');
    await expect(tree.getByRole('treeitem', { name: /manual\.csv/ })).toBeVisible();
    expect(mock.calls.dataUpload.at(-1)).toMatchObject({
      id: 'poetry-skills',
      path: 'manual.csv',
      overwrite: false,
    });

    await hiddenInput.setInputFiles({
      name: 'manual.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from('x,y\n1,2\n'),
    });
    await expect(browser.locator('.browser-error')).toContainText('同名文件已存在');

    await browser.getByRole('checkbox', { name: '覆盖同名' }).check();
    await hiddenInput.setInputFiles({
      name: 'upload.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from('x,y\n1,2\n'),
    });
    await expect(browser.getByRole('status')).toContainText('已上传 1 个文件');
    await expect(tree.getByRole('treeitem', { name: /upload\.csv/ })).toBeVisible();
    expect(mock.calls.dataUpload.at(-1)).toMatchObject({
      id: 'poetry-skills',
      path: 'upload.csv',
      overwrite: true,
    });

    await tree.getByRole('treeitem', { name: /upload\.csv/ }).click();
    await browser.getByRole('button', { name: '删除文件' }).click();
    const dialog = page.getByRole('dialog', { name: '删除数据文件' });
    await dialog.getByRole('button', { name: '删除文件' }).click();
    await expect(browser.getByRole('status')).toContainText('已删除 data/upload.csv');
    await expect(tree.getByRole('treeitem', { name: /upload\.csv/ })).toHaveCount(0);
    expect(mock.calls.dataDelete[0]).toMatchObject({ id: 'poetry-skills', path: 'upload.csv' });
  });

  test('/references 深链重定向到文件页签', async ({ page }) => {
    await setup(page);
    await page.goto('/platform/skills/poetry-skills/references');
    await expect(page).toHaveURL(/\/platform\/skills\/poetry-skills\/files$/);
    await expect(page.getByRole('tab', { name: /^文件/ })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('navigation', { name: '技能文件' })).toBeVisible();
  });

  test('Nacos 来源全链路只读降级', async ({ page }) => {
    await setup(page, { source: 'nacos' });
    await page.goto('/platform/skills');
    await expect(page.locator('.skills-readonly')).toContainText('当前技能存储只读');
    await expect(page.getByRole('button', { name: '新建技能' })).toBeDisabled();
    await expect(page.getByRole('button', { name: '导入' })).toBeDisabled();
    await expect(page.locator('.skills-statline')).toContainText('2 未知');
    await expect(row(page, 'poetry-skills')).toContainText('数据未知');

    await page.goto('/platform/skills/poetry-skills');
    await expect(page.locator('.wb-meta')).toContainText('来源 Nacos');
    await expect(page.locator('.wb-meta .wb-pill.mono')).toHaveText('—');
    await expect(page.locator('.wb-meta')).toContainText('数据未知');
    await expect(page.locator('.wb-meta')).toContainText('无需脚本');
    await expect(page.getByRole('button', { name: '编辑正文' })).toBeDisabled();
    await expect(page.getByRole('tab', { name: /^文件/ })).toContainText('—');
    await expect(page.getByRole('tab', { name: /^版本/ })).toContainText('—');
    await expect(page.getByText('当前来源不提供数据状态。')).toBeVisible();

    const rail = page.getByRole('complementary', { name: '技能标识与危险区' });
    await expect(rail).toContainText('Nacos · 只读');
    await expect(rail.getByRole('button', { name: '删除技能' })).toBeDisabled();
    await expect(rail.getByRole('button', { name: '删除技能' })).toHaveAttribute(
      'title',
      '当前存储只读，无法删除'
    );

    await page.getByRole('tab', { name: /^文件/ }).click();
    await expect(page.getByText('当前来源不提供文件列表')).toBeVisible();
    await expect(page.getByText('当前存储只读，文件编辑入口已禁用。')).toBeVisible();

    await page.getByRole('tab', { name: '数据' }).click();
    await expect(page.locator('.data-unknown')).toContainText('当前来源不提供数据状态');

    await page.getByRole('tab', { name: /^版本/ }).click();
    await expect(page.getByText('当前来源不提供版本时间线').first()).toBeVisible();
  });
});

test.describe('技能编辑器', () => {
  test('保存正文回传 revision/changeNote 并连续生成 v8、v9', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/poetry-skills/edit');
    await expect(page.getByRole('heading', { name: '编辑正文' })).toBeVisible();
    await expect(page.locator('.ed-sub')).toContainText('基于 v7 编辑');

    const editor = page.getByLabel('编辑 SKILL.md');
    await editor.fill(`${SKILL_BODY}\n- 新增一句规则说明。\n`);
    await page.getByPlaceholder('写进版本记录，方便回滚时辨认').fill('补充规则');
    await page.locator('.ed-head-actions').getByRole('button', { name: '保存为 v8' }).click();

    await expect(page.locator('.toast', { hasText: '已保存为 v8' })).toBeVisible();
    expect(mock.calls.updated).toHaveLength(1);
    expect(mock.calls.updated[0].revision).toBe('rev-7');
    expect(mock.calls.updated[0].body).toMatchObject({
      id: 'poetry-skills',
      description: '诗词创作技能',
      changeNote: '补充规则',
      executionEnabled: true,
    });

    await editor.fill(`${SKILL_BODY}\n- 第二次补充。\n`);
    await page.locator('.ed-head-actions').getByRole('button', { name: '保存为 v9' }).click();
    await expect(page.locator('.toast', { hasText: '已保存为 v9' })).toBeVisible();
    expect(mock.calls.updated).toHaveLength(2);
    expect(mock.calls.updated[1].revision).toBe('rev-8');
  });

  test('文件保存不升版本且不触发正文保存', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/poetry-skills/edit');
    await page.getByRole('button', { name: /rules\.md/ }).click();

    const editor = page.getByRole('textbox', { name: '编辑 references/rules.md' });
    await expect(editor).toBeVisible();
    await editor.fill('# 规则\n更新后的判断顺序。\n');
    await page.locator('.ed-savebar').getByRole('button', { name: '保存文件' }).click();

    await expect(page.locator('.toast', { hasText: '已保存 references/rules.md' })).toBeVisible();
    expect(mock.calls.updatedFile).toHaveLength(1);
    expect(mock.calls.updatedFile[0]).toMatchObject({
      skillId: 'poetry-skills',
      path: 'references/rules.md',
      revision: 'rev-7',
      body: { content: '# 规则\n更新后的判断顺序。\n' },
    });
    expect(mock.calls.updated).toHaveLength(0);
    await expect(page.locator('.ed-dirty-state')).toContainText('已同步');
  });

  test('409 冲突条提供查看差异、另存为 .md 与重新加载三个出口', async ({ page }) => {
    await setup(page, { conflict: true });
    await page.goto('/platform/skills/poetry-skills/edit');
    const editor = page.getByLabel('编辑 SKILL.md');
    await editor.fill(`${SKILL_BODY}\n- 冲突前的本地修改。\n`);
    await page.locator('.ed-head-actions').getByRole('button', { name: '保存为 v8' }).click();

    const bar = page.locator('.conflict-bar');
    await expect(bar).toContainText('把该技能更新到了 v8');
    await expect(bar).toContainText('请选择一个出口继续');

    await bar.getByRole('button', { name: '查看差异' }).click();
    await expect(page.getByRole('region', { name: /与\s*v8\s*的差异/ })).toBeVisible();

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      bar.getByRole('button', { name: '另存为 .md' }).click(),
    ]);
    expect(download.suggestedFilename()).toBe('poetry-skills-draft.md');

    await bar.getByRole('button', { name: '重新加载' }).click();
    const dialog = page.getByRole('dialog', { name: '重新加载最新版本？' });
    await dialog.getByRole('button', { name: '丢弃并重新加载' }).click();
    await expect(page.locator('.toast', { hasText: '已载入最新版本，本地修改已丢弃' })).toBeVisible();
    await expect(page.locator('.conflict-bar')).toHaveCount(0);

    await page.getByRole('tab', { name: '源码' }).click();
    await expect(page.getByLabel('编辑 SKILL.md')).toHaveValue(SKILL_BODY);
  });

  test('离开编辑器保留草稿，再次打开可恢复', async ({ page }) => {
    await setup(page);
    await page.goto('/platform/skills/poetry-skills/edit');
    const marker = '- 本地未保存的改动。';
    await page.getByLabel('编辑 SKILL.md').fill(`${SKILL_BODY}\n${marker}\n`);
    await page.locator('.ed-head-actions').getByRole('button', { name: '取消' }).click();

    const leaveDialog = page.getByRole('dialog', { name: '离开编辑器？' });
    await leaveDialog.getByRole('button', { name: '离开并保留草稿' }).click();
    await expect(page).toHaveURL(/\/platform\/skills\/poetry-skills\/overview$/);

    await page.goto('/platform/skills/poetry-skills/edit');
    const draftBar = page.locator('.draft-bar');
    await expect(draftBar).toContainText('发现 12:00 的本地草稿，可恢复继续编辑。');
    await draftBar.getByRole('button', { name: '恢复草稿' }).click();

    await expect(page.locator('.draft-bar')).toHaveCount(0);
    await expect(page.getByLabel('编辑 SKILL.md')).toHaveValue(new RegExp(marker.trim().slice(2)));
    await expect(page.locator('.ed-sub')).toContainText('草稿保存于 12:00');
  });

  test('预览清洗掉脚本与图片，不执行内联代码', async ({ page }) => {
    await setup(page);
    await page.goto('/platform/skills/poetry-skills/edit');
    const hostile = [
      '## 角色',
      '<img src=x onerror="window.__xss=1">',
      '<script>window.__xss=2</script>',
      SKILL_BODY,
    ].join('\n');
    await page.getByLabel('编辑 SKILL.md').fill(hostile);
    await page.getByRole('tab', { name: '预览' }).click();

    const preview = page.locator('.md-preview');
    await expect(preview).toBeVisible();
    await expect(preview).toContainText('角色');
    await expect(preview.locator('img')).toHaveCount(0);
    await expect(preview.locator('script')).toHaveCount(0);
    expect(await page.evaluate(() => window.__xss)).toBeUndefined();
  });
});

test.describe('新建与导入', () => {
  test('空白新建受质量门槛约束，通过后 POST 不带 templateId', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/new/editor');
    await expect(page.getByRole('heading', { name: '编辑正文' })).toBeVisible();
    await expect(page.locator('.rail-checks li.failed')).toHaveCount(3);
    const save = page.locator('.ed-head-actions').getByRole('button', { name: '保存', exact: true });
    await expect(save).toBeDisabled();

    await page.getByPlaceholder('payroll-policy-notes').fill('payroll-test-skill');
    await page.getByPlaceholder('一句话说明技能用途').fill('空白新建的测试技能');
    await page.getByLabel('编辑 SKILL.md').fill(SKILL_BODY);
    await expect(page.locator('.rail-checks li.failed')).toHaveCount(0);
    await expect(save).toBeEnabled();

    await save.click();
    await expect(page.locator('.toast', { hasText: '技能 payroll-test-skill 已创建' })).toBeVisible();
    await expect(page).toHaveURL(/\/platform\/skills\/payroll-test-skill\/overview$/);
    await expect(page.getByRole('heading', { name: 'payroll-test-skill' })).toBeVisible();
    expect(mock.calls.created).toHaveLength(1);
    expect(mock.calls.created[0]).toMatchObject({
      id: 'payroll-test-skill',
      description: '空白新建的测试技能',
      content: SKILL_BODY,
    });
    expect('templateId' in mock.calls.created[0]).toBe(false);
  });

  test('模板新建带出说明与正文并提交 templateId', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/new');
    await expect(page.getByRole('heading', { name: '新建技能' })).toBeVisible();
    await expect(page.getByText('从空白创建')).toBeVisible();
    await expect(page.getByText('导入项目').first()).toBeVisible();

    await page.getByRole('button', { name: '选择模板' }).click();
    const picker = page.getByRole('dialog', { name: '选择模板' });
    await expect(picker.locator('.tpl-row')).toHaveCount(4);
    await picker.getByRole('button', { name: '使用' }).first().click();

    await expect(page).toHaveURL(/templateId=policy-qa/);
    await expect(page.getByPlaceholder('一句话说明技能用途')).toHaveValue('按政策原文回答口径类问题');
    await expect(page.getByLabel('编辑 SKILL.md')).toHaveValue(SKILL_BODY);

    await page.getByPlaceholder('payroll-policy-notes').fill('template-skill');
    await page.locator('.ed-head-actions').getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.locator('.toast', { hasText: '技能 template-skill 已创建' })).toBeVisible();
    expect(mock.calls.created).toHaveLength(1);
    expect(mock.calls.created[0]).toMatchObject({ id: 'template-skill', templateId: 'policy-qa' });
  });

  test('Git 导入：预检强制覆盖、确认需勾选覆盖并带 revision', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/import');
    await expect(page.getByRole('heading', { name: '检查导入内容' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: '面包屑' })).toContainText('导入项目');

    await page.getByPlaceholder('https://github.com/org/skill.git').fill(
      'https://github.com/org/poetry-skills.git'
    );
    await page.getByPlaceholder('main').fill('release');
    await page.getByPlaceholder('留空按 SKILL.md 生成').fill('poetry-skills');
    await page.getByRole('button', { name: '下一步：预检' }).click();

    await expect(page.locator('.warn-bar')).toContainText('已存在（当前 v7）');
    await expect(page.locator('.warn-bar')).toContainText('生成 v8 并覆盖 1 个文件');
    const legend = page.locator('.status-legend');
    await expect(legend).toContainText('新增 2');
    await expect(legend).toContainText('覆盖 1');
    await expect(legend).toContainText('不变 1');

    const rail = page.getByRole('complementary', { name: '导入摘要' });
    await expect(rail).toContainText('bash scripts/setup.sh');
    await expect(rail).toContainText('1024.0 MB');
    const go = rail.getByRole('button', { name: '确认导入并生成 v8' });
    await expect(go).toBeDisabled();

    await rail.getByRole('checkbox', { name: /覆盖同名技能/ }).check();
    await expect(go).toBeEnabled();
    await go.click();

    await expect(page.locator('.toast', { hasText: '已导入「poetry-skills」，当前 v8' })).toBeVisible();
    await expect(page).toHaveURL(/\/platform\/skills\/poetry-skills\/overview$/);
    expect(mock.calls.previewGit[0]).toMatchObject({
      revision: '',
      body: {
        url: 'https://github.com/org/poetry-skills.git',
        ref: 'release',
        id: 'poetry-skills',
        overwrite: true,
        dryRun: true,
      },
    });
    expect(mock.calls.importedGit[0].revision).toBe('rev-7');
    expect(mock.calls.importedGit[0].body).toMatchObject({
      overwrite: true,
      dryRun: false,
    });
  });

  test('ZIP 导入：multipart 预检与导入都带 revision', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/import');
    await page.getByRole('tab', { name: 'ZIP 包' }).click();
    await page.locator('input[type=file]').setInputFiles({
      name: 'poetry.zip',
      mimeType: 'application/zip',
      buffer: Buffer.from('PK\u0003\u0004placeholder'),
    });
    await expect(page.locator('.file-picked')).toContainText('poetry.zip');
    await page.getByPlaceholder('留空按包内目录名生成').fill('poetry-skills');
    await page.getByRole('button', { name: '下一步：预检' }).click();

    await expect(page.locator('.warn-bar')).toContainText('已存在（当前 v7）');
    expect(mock.calls.previewZip[0]).toMatchObject({
      revision: '',
      body: { id: 'poetry-skills', overwrite: true, dryRun: true },
    });

    const rail = page.getByRole('complementary', { name: '导入摘要' });
    await rail.getByRole('checkbox', { name: /覆盖同名技能/ }).check();
    await rail.getByRole('button', { name: '确认导入并生成 v8' }).click();
    await expect(page.locator('.toast', { hasText: '已导入「poetry-skills」，当前 v8' })).toBeVisible();
    expect(mock.calls.importedZip[0].body).toMatchObject({
      id: 'poetry-skills',
      overwrite: true,
      dryRun: false,
      revision: 'rev-7',
    });
  });
});

test.describe('数据页与删除', () => {
  test('初始化失败状态、重建数据与删除技能（revision + 输入确认）', async ({ page }) => {
    const mock = await setup(page);
    await page.goto('/platform/skills/payroll-policy-notes/data');
    await expect(page.locator('.data-status')).toContainText('初始化失败');
    await expect(page.locator('.data-message')).toContainText('初始化失败：依赖下载超时');

    await page.getByRole('button', { name: '重新初始化' }).click();
    const initDialog = page.getByRole('dialog', { name: '初始化技能数据' });
    await expect(initDialog).toContainText('将在独立初始化容器中执行');
    await initDialog.getByRole('button', { name: '开始初始化' }).click();
    await expect(page.locator('.toast', { hasText: '已提交初始化，完成后自动刷新' })).toBeVisible();
    expect(mock.calls.dataInit[0]).toMatchObject({
      id: 'payroll-policy-notes',
      body: { overwrite: false },
    });

    await page.getByRole('button', { name: '删除技能' }).click();
    const deleteDialog = page.getByRole('dialog', { name: '删除技能 payroll-policy-notes' });
    await deleteDialog.getByRole('button', { name: '删除技能' }).click();
    await expect(deleteDialog.locator('.error-text')).toContainText('内容不一致，请重新输入');

    await deleteDialog.getByPlaceholder('payroll-policy-notes').fill('payroll-policy-notes');
    await deleteDialog.getByRole('button', { name: '删除技能' }).click();
    await expect(page.locator('.toast', { hasText: '技能 payroll-policy-notes 已删除' })).toBeVisible();
    await expect(page).toHaveURL(/\/platform\/skills$/);
    expect(mock.calls.deleted[0]).toMatchObject({
      kind: 'skill',
      id: 'payroll-policy-notes',
      revision: 'rev-3',
    });
  });
});
