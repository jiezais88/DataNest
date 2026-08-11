/* ============================================================
   DataNest Sprint 9 原型 — 交互逻辑
   视图切换 / 抽屉页签 / 弹窗抽屉开闭 / 分段选择
   ============================================================ */

// ---------- 视图切换 ----------
function switchView(name) {
  document.querySelectorAll('.view').forEach(function (v) { v.classList.remove('active'); });
  var target = document.querySelector('.view[data-view="' + name + '"]');
  if (target) target.classList.add('active');
  document.querySelectorAll('.prototype-switch button').forEach(function (b) {
    b.classList.toggle('active', b.getAttribute('data-view') === name);
  });
  window.scrollTo(0, 0);
  // 切回视图时恢复其默认打开的抽屉/弹窗
  document.querySelectorAll('.drawer-mask, .modal-mask').forEach(function (m) {
    m.classList.toggle('open', m.closest('.view') === target && m.hasAttribute('data-default-open'));
  });
}

document.querySelectorAll('.prototype-switch button').forEach(function (b) {
  b.addEventListener('click', function () { switchView(b.getAttribute('data-view')); });
});

// ---------- 抽屉/弹窗开闭 ----------
function closeLayer(id) {
  var el = document.getElementById(id);
  if (el) el.classList.remove('open');
}
function openLayer(id) {
  var el = document.getElementById(id);
  if (el) el.classList.add('open');
}
// 点遮罩空白处关闭抽屉
document.querySelectorAll('.drawer-mask').forEach(function (mask) {
  mask.addEventListener('click', function (e) { if (e.target === mask) mask.classList.remove('open'); });
});

// ---------- 抽屉内页签 ----------
function drawerTab(el) {
  var nav = el.closest('.tabs-nav');
  nav.querySelectorAll('.tab-item').forEach(function (t) { t.classList.remove('active'); });
  el.classList.add('active');
}

// ---------- 分段选择（单选组） ----------
document.querySelectorAll('.seg').forEach(function (seg) {
  seg.querySelectorAll('.seg-item').forEach(function (item) {
    item.addEventListener('click', function () {
      seg.querySelectorAll('.seg-item').forEach(function (i) { i.classList.remove('active'); });
      item.classList.add('active');
    });
  });
});

// ---------- 初始化：标记各视图默认打开的浮层 ----------
document.querySelectorAll('.view').forEach(function (v) {
  v.querySelectorAll('.drawer-mask.open, .modal-mask.open').forEach(function (m) {
    m.setAttribute('data-default-open', '1');
  });
});
