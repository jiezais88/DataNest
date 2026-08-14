// DataNest Sprint 11 原型交互：视图切换 + 弹窗/抽屉 + 权限勾选

// ---------- 视图切换 ----------
const switchBtns = document.querySelectorAll('.prototype-switch button');
const views = document.querySelectorAll('.view');

function switchView(name) {
    switchBtns.forEach(b => b.classList.toggle('active', b.getAttribute('data-view') === name));
    views.forEach(v => v.classList.toggle('active', v.getAttribute('data-view') === name));
    // 切视图时关闭弹窗/抽屉，避免残留遮罩
    document.querySelectorAll('.modal-mask.open').forEach(m => m.classList.remove('open'));
    closeDrawer('audit-drawer');
}

switchBtns.forEach(b => b.addEventListener('click', () => switchView(b.getAttribute('data-view'))));

// ---------- 弹窗 ----------
function openModal(id) {
    document.getElementById(id).classList.add('open');
}
function closeModal(id) {
    document.getElementById(id).classList.remove('open');
}
// 点遮罩关闭（弹窗）
document.querySelectorAll('.modal-mask').forEach(m => {
    m.addEventListener('click', (e) => {
        if (e.target === m) m.classList.remove('open');
    });
});

// ---------- 抽屉 ----------
function openDrawer(id) {
    document.getElementById(id).classList.add('open');
    document.getElementById(id + '-panel').classList.add('open');
}
function closeDrawer(id) {
    document.getElementById(id).classList.remove('open');
    document.getElementById(id + '-panel').classList.remove('open');
}

// ---------- 头像下拉切换 ----------
document.querySelectorAll('.tn-user').forEach(u => {
    u.addEventListener('click', (e) => {
        e.stopPropagation();
        u.classList.toggle('open');
    });
});
document.addEventListener('click', () => {
    document.querySelectorAll('.tn-user').forEach(u => u.classList.remove('open'));
});

// ---------- 权限勾选 chip 切换（角色弹窗内） ----------
document.querySelectorAll('.perm-chip').forEach(chip => {
    chip.addEventListener('click', () => {
        chip.classList.toggle('on');
        // 联动：组内全开/全关时同步组头勾选
        const group = chip.closest('.perm-group');
        if (group) {
            const chips = group.querySelectorAll('.perm-chip');
            const onCount = [...chips].filter(c => c.classList.contains('on')).length;
            const check = group.querySelector('.perm-group-head .tree-check');
            if (check) {
                if (onCount === 0) {
                    check.classList.remove('checked');
                    check.innerHTML = '';
                } else if (onCount === chips.length) {
                    check.classList.add('checked');
                    check.innerHTML = '<svg class="hi-sm"><use href="#i-check"/></svg>';
                } else {
                    check.classList.remove('checked');
                    check.classList.add('half');
                    check.innerHTML = '<svg class="hi-sm"><use href="#i-check"/></svg>';
                }
            }
        }
    });
});

// ---------- 权限树节点勾选（权限配置页） ----------
document.querySelectorAll('.tree-check:not(.disabled)').forEach(check => {
    check.addEventListener('click', (e) => {
        e.stopPropagation();
        const checked = check.classList.contains('checked');
        if (checked) {
            check.classList.remove('checked');
            check.innerHTML = '';
        } else {
            check.classList.add('checked');
            check.innerHTML = '<svg class="hi-sm"><use href="#i-check"/></svg>';
        }
    });
});

// ---------- 树节点展开/折叠 ----------
document.querySelectorAll('.tree-row .chev').forEach(chev => {
    chev.addEventListener('click', (e) => {
        e.stopPropagation();
        chev.classList.toggle('open');
        const row = chev.closest('.tree-row');
        const children = row.nextElementSibling;
        if (children && children.classList.contains('tree-children')) {
            children.style.display = chev.classList.contains('open') ? 'block' : 'none';
        }
    });
});

// ---------- 平台概览折叠 ----------
function toggleCollapse(id) {
    const bar = document.getElementById(id);
    if (bar) bar.classList.toggle('open');
}

// ---------- 首页「需要你关注」空态演示 ----------
function toggleAttentionDemo() {
    const card = document.getElementById('attention-card');
    const has = document.getElementById('attention-has');
    const empty = document.getElementById('attention-empty');
    const count = document.getElementById('attention-count');
    if (!card || !has || !empty) return;
    const isEmpty = card.classList.contains('is-empty');
    card.classList.toggle('is-empty', !isEmpty);
    card.classList.toggle('has-alert', isEmpty);
    has.style.display = isEmpty ? 'block' : 'none';
    empty.style.display = isEmpty ? 'none' : 'flex';
    if (count) count.style.display = isEmpty ? 'inline-flex' : 'none';
    const btn = document.querySelector('#attention-card .demo-toggle');
    if (btn) btn.textContent = isEmpty ? '演示空态' : '演示有异常';
}

// ---------- 首页趋势图类型筛选（原型示意：仅切换激活态，不重绘数据） ----------
document.querySelectorAll('.chart-filters .cf-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        const group = btn.closest('.chart-filters');
        if (!group) return;
        group.querySelectorAll('.cf-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
    });
});

// ---------- 平台概览摘要段：点击不触发整体折叠（演示微型入口的可点性） ----------
document.querySelectorAll('.summary-item').forEach(item => {
    item.addEventListener('click', (e) => e.stopPropagation());
});
