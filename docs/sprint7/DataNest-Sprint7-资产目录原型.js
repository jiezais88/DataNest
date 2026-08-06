/* ============================================================
   DataNest Sprint7 资产目录原型 — 交互逻辑
   视图切换 / antd Tabs / 弹窗开关
   ============================================================ */

// 视图切换（原型切换条 + 侧边栏跳转）
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

  // 关闭所有弹窗，重置滚动
  closeAllModals();
  window.scrollTo(0, 0);
}

// 绑定切换条
document.querySelectorAll('.prototype-switch button').forEach(function(b) {
  b.addEventListener('click', function() {
    switchView(b.getAttribute('data-view'));
  });
});

// 详情页 antd Tabs 切换
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

// 对每个 .view 内的 tabs 分别绑定（避免跨 view 串扰）
document.querySelectorAll('.view').forEach(function(v) {
  bindTabs(v);
});

// 弹窗开关
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
    m.style.display = 'none';
  });
}

// 点击弹窗遮罩关闭
document.querySelectorAll('.modal-mask').forEach(function(mask) {
  mask.addEventListener('click', function(e) {
    if (e.target === mask) mask.style.display = 'none';
  });
});

// 初始化：默认显示数据资产视图
window.addEventListener('DOMContentLoaded', function() {
  document.querySelectorAll('.view').forEach(function(v) {
    var isDefault = v.getAttribute('data-view') === 'assets';
    v.style.display = isDefault ? 'block' : 'none';
  });
});
