/* ============================================================
   DataNest Sprint8 原型 — 交互逻辑
   视图切换 / antd Tabs / 弹窗 / 抽屉 / 向导步骤 / 标签编辑
   ============================================================ */

// ---------- 视图切换 ----------
function switchView(name) {
  document.querySelectorAll('.view').forEach(function(v) {
    v.style.display = 'none';
  });
  var target = document.querySelector('.view[data-view="' + name + '"]');
  if (target) target.style.display = 'block';

  // 同步切换条高亮
  document.querySelectorAll('.prototype-switch button').forEach(function(b) {
    b.classList.toggle('active', b.getAttribute('data-view') === name);
  });

  // 关闭所有弹窗/抽屉，重置滚动
  closeAllModals();
  closeAllDrawers();
  window.scrollTo(0, 0);
}

// 绑定切换条
document.querySelectorAll('.prototype-switch button').forEach(function(b) {
  b.addEventListener('click', function() {
    switchView(b.getAttribute('data-view'));
  });
});

// ---------- antd Tabs 切换（每个 .view 内独立绑定） ----------
function bindTabs(container) {
  container.querySelectorAll('.ant-tabs-tab[data-tab]').forEach(function(tab) {
    tab.addEventListener('click', function() {
      container.querySelectorAll('.ant-tabs-tab').forEach(function(t) { t.classList.remove('active'); });
      tab.classList.add('active');
      var name = tab.getAttribute('data-tab');
      container.querySelectorAll('.tab-panel').forEach(function(p) { p.style.display = 'none'; });
      var panel = container.querySelector('.tab-panel[data-panel="' + name + '"]');
      if (panel) panel.style.display = 'block';
    });
  });
}
document.querySelectorAll('.view').forEach(function(v) {
  bindTabs(v);
});

// ---------- 弹窗开关 ----------
function openModal(id) {
  var el = document.getElementById(id);
  if (el) el.style.display = 'flex';
}
function closeModal(id) {
  var el = document.getElementById(id);
  if (el) el.style.display = 'none';
}
function closeAllModals() {
  document.querySelectorAll('.modal-mask').forEach(function(m) {
    if (m.classList.contains('drawer-mask')) return;
    m.style.display = 'none';
  });
}
// 点击弹窗遮罩关闭
document.querySelectorAll('.modal-mask').forEach(function(mask) {
  mask.addEventListener('click', function(e) {
    if (e.target === mask) mask.style.display = 'none';
  });
});

// ---------- 抽屉开关 ----------
function openDrawer(id) {
  var el = document.getElementById(id);
  if (el) el.style.display = 'flex';
}
function closeDrawer(id) {
  var el = document.getElementById(id);
  if (el) el.style.display = 'none';
}
function closeAllDrawers() {
  document.querySelectorAll('.drawer-mask').forEach(function(m) {
    m.style.display = 'none';
  });
}
document.querySelectorAll('.drawer-mask').forEach(function(mask) {
  mask.addEventListener('click', function(e) {
    if (e.target === mask) mask.style.display = 'none';
  });
});

// ---------- CDC 向导步骤 ----------
function wizGo(step) {
  // 隐藏所有步骤面板
  document.querySelectorAll('.wiz-step-panel').forEach(function(p) {
    p.style.display = 'none';
  });
  var panel = document.querySelector('.wiz-step-panel[data-step="' + step + '"]');
  if (panel) panel.style.display = 'block';

  // 更新步骤条状态
  document.querySelectorAll('.wiz-step').forEach(function(s) {
    var n = parseInt(s.getAttribute('data-step'), 10);
    s.classList.remove('active', 'done');
    if (n < step) s.classList.add('done');
    if (n === step) s.classList.add('active');
  });
  document.querySelectorAll('.wiz-step-line').forEach(function(l) {
    var n = parseInt(l.getAttribute('data-step'), 10);
    l.classList.toggle('done', n < step);
  });

  // 更新底部按钮
  var backBtn = document.querySelector('.wizard-foot .wz-back');
  var nextBtn = document.querySelector('.wizard-foot .wz-next');
  var finishBtn = document.querySelector('.wizard-foot .wz-finish');
  if (backBtn) backBtn.style.display = step > 1 ? 'inline-flex' : 'none';
  if (step < 4) {
    if (nextBtn) nextBtn.style.display = 'inline-flex';
    if (finishBtn) finishBtn.style.display = 'none';
  } else {
    if (nextBtn) nextBtn.style.display = 'none';
    if (finishBtn) finishBtn.style.display = 'inline-flex';
  }
}
function wizNext() {
  var active = document.querySelector('.wiz-step.active');
  if (!active) return;
  var cur = parseInt(active.getAttribute('data-step'), 10);
  if (cur < 4) wizGo(cur + 1);
}
function wizPrev() {
  var active = document.querySelector('.wiz-step.active');
  if (!active) return;
  var cur = parseInt(active.getAttribute('data-step'), 10);
  if (cur > 1) wizGo(cur - 1);
}
// 初始化向导（若存在）
document.addEventListener('DOMContentLoaded', function() {
  if (document.querySelector('.wiz-step')) wizGo(1);
});

// ---------- 标签编辑（详情页） ----------
function addTagInput(btn) {
  var wrap = btn.closest('.d-tags');
  // 移除已有输入框
  var existing = wrap.querySelector('.tag-input');
  if (existing) return;
  var input = document.createElement('div');
  input.className = 'tag-input';
  input.innerHTML = '<input placeholder="标签名，回车创建" maxlength="12"><svg class="hi"><use href="#i-check"/></svg>';
  btn.style.display = 'none';
  wrap.insertBefore(input, btn);
  var inp = input.querySelector('input');
  inp.focus();
  inp.addEventListener('keydown', function(e) {
    if (e.key === 'Enter' && inp.value.trim()) {
      var chip = document.createElement('span');
      chip.className = 'tag-chip';
      chip.innerHTML = '<span>' + inp.value.trim() + '</span><svg class="x"><use href="#i-xmark"/></svg>';
      chip.querySelector('.x').addEventListener('click', function() { chip.remove(); });
      input.replaceWith(chip);
      btn.style.display = 'inline-flex';
    }
  });
}

// ---------- 收藏/关注按钮切换 ----------
document.querySelectorAll('.fav-btn').forEach(function(btn) {
  btn.addEventListener('click', function() {
    btn.classList.toggle('active');
  });
});

// ---------- 标签筛选（资产列表：点击标签云选中） ----------
document.querySelectorAll('.tag-cloud .tag-chip').forEach(function(chip) {
  chip.addEventListener('click', function() {
    var cloud = chip.closest('.tag-cloud');
    cloud.querySelectorAll('.tag-chip').forEach(function(c) { c.classList.remove('active'); });
    chip.classList.add('active');
  });
});

// ---------- 初始化：默认显示资产视图 ----------
window.addEventListener('DOMContentLoaded', function() {
  document.querySelectorAll('.view').forEach(function(v) {
    var isDefault = v.getAttribute('data-view') === 'assets';
    v.style.display = isDefault ? 'block' : 'none';
  });
});
