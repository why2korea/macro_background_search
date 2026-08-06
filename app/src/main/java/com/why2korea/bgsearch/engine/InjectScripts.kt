package com.why2korea.bgsearch.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView 에 주입하는 JavaScript 문자열 모음.
 *
 * 참고 프로젝트(macro_search_manpodae)의 InjectScripts 를 이식한 뒤
 *  - 문자열 정규화(공백 접기 + 소문자화) 비교
 *  - 2차 문자열 N개 동시 스캔 (OR / AND)
 * 를 추가했다.
 *
 * 주의
 *  - 모든 스크립트는 마지막에 JSON.stringify(...) 결과(문자열)를 반환한다.
 *    → evaluateJavascript 콜백에는 "JSON 문자열이 다시 JSON 인코딩된" 값이 넘어온다.
 *      WebController.evalJson() 이 두 번 풀어서 JSONObject 로 만든다.
 *  - JS 안에서 백틱 템플릿 리터럴과 '＄' 기호를 쓰지 않는다.
 *    (Kotlin raw string 의 템플릿 치환과 충돌하기 때문)
 *  - alert()/confirm() 은 절대 쓰지 않는다. (WebView 블로킹 방지)
 *  - cross-origin iframe 접근은 전부 try/catch 로 무시하고 개수만 센다.
 */
object InjectScripts {

    /** 문자열을 JS 리터럴로 안전하게 감싼다. */
    fun q(s: String): String = JSONObject.quote(s)

    /** 문자열 목록을 JS 배열 리터럴로 감싼다. */
    fun qArray(list: List<String>): String = JSONArray(list).toString()

    /** 모든 스크립트에 공통으로 삽입되는 헬퍼 라이브러리. */
    private const val COMMON = """
var BG = (function(){
  var skipped = 0;

  /* 공백(NBSP/제로폭 포함) 접기 + 소문자화 정규화 */
  function norm(s){
    if(!s) return '';
    try{
      return String(s).replace(/[\s\u00a0\u200b\u200c\u200d\ufeff]+/g, ' ').trim().toLowerCase();
    }catch(e){ return ''; }
  }

  function docs(){
    var list = [document];
    skipped = 0;
    try{
      var ifr = document.getElementsByTagName('iframe');
      for(var i=0;i<ifr.length;i++){
        try{
          var d = ifr[i].contentDocument;
          if(d && d.body){ list.push(d); } else { skipped++; }
        }catch(e){ skipped++; }
      }
    }catch(e){}
    return list;
  }

  function skippedCount(){ return skipped; }

  function textOf(el){
    var t = '';
    try{ t = el.innerText; }catch(e){}
    if(!t){ try{ t = el.textContent; }catch(e){} }
    return t || '';
  }

  function visible(el){
    try{
      var r = el.getBoundingClientRect();
      if(r.width <= 0 || r.height <= 0) return false;
      var win = el.ownerDocument.defaultView || window;
      var st = win.getComputedStyle(el);
      if(!st) return true;
      if(st.visibility === 'hidden' || st.display === 'none' || st.opacity === '0') return false;
      return true;
    }catch(e){ return false; }
  }

  /* 정규화된 문자열을 포함하는 요소 중 가장 안쪽(innermost) 요소.
     자식 중에 같은 문자열을 가진 요소가 있으면 건너뛴다.
     보이는 요소를 우선하고, 없으면 숨은 요소라도 반환한다. */
  function findInnermost(doc, needle){
    var fallback = null;
    var all;
    try{ all = doc.querySelectorAll('body *'); }catch(e){ return null; }
    for(var i=0;i<all.length;i++){
      var el = all[i];
      var tg = el.tagName;
      if(tg === 'SCRIPT' || tg === 'STYLE' || tg === 'NOSCRIPT' || tg === 'TEMPLATE') continue;
      var t = norm(textOf(el));
      if(!t || t.indexOf(needle) === -1) continue;
      var deeper = false;
      var ch = el.children;
      for(var j=0;j<ch.length;j++){
        if(norm(textOf(ch[j])).indexOf(needle) !== -1){ deeper = true; break; }
      }
      if(deeper) continue;
      if(visible(el)) return el;
      if(!fallback) fallback = el;
    }
    return fallback;
  }

  /* 메인 문서 + same-origin iframe 전체에서 찾는다. (needle 은 정규화된 값) */
  function findAnyDoc(needle){
    var ds = docs();
    for(var i=0;i<ds.length;i++){
      var el = findInnermost(ds[i], needle);
      if(el) return el;
    }
    return null;
  }

  /* 메인 문서 + same-origin iframe 의 innerText 를 모두 합쳐 정규화한다. */
  function allTextNorm(){
    var ds = docs();
    var out = '';
    for(var i=0;i<ds.length;i++){
      try{
        var b = ds[i].body;
        if(!b) continue;
        out += (b.innerText || b.textContent || '') + ' ';
      }catch(e){}
    }
    return norm(out);
  }

  /* 스크롤 컨테이너 자동 판별.
     body/documentElement 가 스크롤되지 않으면
     overflow-y 가 auto|scroll 이고 scrollHeight > clientHeight 인 가장 큰 요소를 고른다. */
  function mainTarget(){
    var de = document.scrollingElement || document.documentElement || document.body;
    try{
      if(de && de.scrollHeight > de.clientHeight + 4) return de;
    }catch(e){}
    var best = null, bestArea = 0;
    var all;
    try{ all = document.querySelectorAll('div,main,section,article,ul,ol,tbody'); }catch(e){ return de; }
    for(var i=0;i<all.length;i++){
      var el = all[i];
      var st;
      try{ st = window.getComputedStyle(el); }catch(e){ continue; }
      if(!st) continue;
      var oy = st.overflowY;
      if(oy !== 'auto' && oy !== 'scroll') continue;
      if(el.scrollHeight <= el.clientHeight + 4) continue;
      var r = el.getBoundingClientRect();
      var area = r.width * r.height;
      if(area > bestArea){ bestArea = area; best = el; }
    }
    return best || de;
  }

  /* same-origin iframe 중 스크롤 가능한 것들. */
  function frameTargets(){
    var out = [];
    try{
      var ifr = document.getElementsByTagName('iframe');
      for(var i=0;i<ifr.length;i++){
        try{
          var d = ifr[i].contentDocument;
          if(!d) continue;
          var se = d.scrollingElement || d.documentElement;
          if(se && se.scrollHeight > se.clientHeight + 4) out.push(se);
        }catch(e){}
      }
    }catch(e){}
    return out;
  }

  function atBottom(el){
    try{
      return (el.scrollTop + el.clientHeight) >= (el.scrollHeight - 4);
    }catch(e){ return true; }
  }

  /* 프레임워크 대응을 위해 pointer/mouse 이벤트까지 순서대로 발생시킨 뒤 click() 호출. */
  function fireClick(el){
    try{ el.scrollIntoView({block:'center', inline:'center'}); }
    catch(e){ try{ el.scrollIntoView(); }catch(e2){} }
    var cx = 0, cy = 0;
    try{
      var r = el.getBoundingClientRect();
      cx = r.left + r.width / 2;
      cy = r.top + r.height / 2;
    }catch(e){}
    var opts = {bubbles:true, cancelable:true, view:window, clientX:cx, clientY:cy, button:0};
    try{ el.focus(); }catch(e){}
    try{ if(window.PointerEvent){ el.dispatchEvent(new PointerEvent('pointerdown', opts)); } }catch(e){}
    try{ el.dispatchEvent(new MouseEvent('mousedown', opts)); }catch(e){}
    try{ if(window.PointerEvent){ el.dispatchEvent(new PointerEvent('pointerup', opts)); } }catch(e){}
    try{ el.dispatchEvent(new MouseEvent('mouseup', opts)); }catch(e){}
    try{
      if(typeof el.click === 'function'){ el.click(); }
      else { el.dispatchEvent(new MouseEvent('click', opts)); }
    }catch(e){}
  }

  return {
    norm: norm,
    docs: docs,
    skippedCount: skippedCount,
    textOf: textOf,
    visible: visible,
    findInnermost: findInnermost,
    findAnyDoc: findAnyDoc,
    allTextNorm: allTextNorm,
    mainTarget: mainTarget,
    frameTargets: frameTargets,
    atBottom: atBottom,
    fireClick: fireClick
  };
})();
"""

    /**
     * 1차 문자열을 찾아 클릭한다. 없으면 found=false 로 돌아온다.
     * 클릭 직전에 MutationObserver 를 설치해 DOM 변화 여부를 추적한다.
     */
    fun clickPrimary(text: String): String = """
(function(){
  try{
    $COMMON
    var needle = BG.norm(${q(text)});
    if(!needle){
      return JSON.stringify({ok:false, found:false, reason:'empty'});
    }
    var el = BG.findAnyDoc(needle);
    if(!el){
      return JSON.stringify({ok:true, found:false, reason:'notfound', skipped: BG.skippedCount()});
    }
    window.__bgChanged = false;
    window.__bgHref = location.href;
    try{
      if(window.__bgObs){ window.__bgObs.disconnect(); }
      window.__bgObs = new MutationObserver(function(){ window.__bgChanged = true; });
      window.__bgObs.observe(document.documentElement, {childList:true, subtree:true});
    }catch(e){}
    BG.fireClick(el);
    return JSON.stringify({
      ok:true,
      found:true,
      tag: el.tagName,
      snippet: BG.textOf(el).replace(/\s+/g,' ').slice(0,60),
      skipped: BG.skippedCount()
    });
  }catch(e){
    return JSON.stringify({ok:false, found:false, reason:'error', err:String(e)});
  }
})();
"""

    /** 클릭 이후 DOM 변화 / URL 변화가 있었는지 확인. (페이지 이동 · 모달 · SPA 라우팅 공통) */
    fun checkChanged(): String = """
(function(){
  try{
    var base = window.__bgHref || '';
    return JSON.stringify({
      ok:true,
      changed: !!window.__bgChanged,
      moved: (location.href !== base),
      href: location.href
    });
  }catch(e){
    return JSON.stringify({ok:false, changed:false, moved:false});
  }
})();
"""

    /**
     * 2차 문자열 목록을 한 번에 스캔한다.
     * matched  : 발견된 문자열 배열
     * any/all  : OR / AND 판정 결과
     */
    fun scanSecondary(list: List<String>): String = """
(function(){
  try{
    $COMMON
    var raw = ${qArray(list)};
    var txt = BG.allTextNorm();
    var matched = [];
    var total = 0;
    for(var i=0;i<raw.length;i++){
      var n = BG.norm(raw[i]);
      if(!n) continue;
      total++;
      if(txt.indexOf(n) >= 0){ matched.push(raw[i]); }
    }
    return JSON.stringify({
      ok:true,
      matched: matched,
      any: matched.length > 0,
      all: (total > 0 && matched.length === total),
      skipped: BG.skippedCount(),
      len: txt.length
    });
  }catch(e){
    return JSON.stringify({ok:false, matched:[], any:false, all:false, err:String(e)});
  }
})();
"""

    /** 한 스텝 스크롤. 아직 바닥이 아닌 첫 컨테이너를 내린다. */
    fun scrollStep(ratio: Float): String = """
(function(){
  try{
    $COMMON
    var ratio = $ratio;
    if(!(ratio > 0.05)) ratio = 0.8;
    if(ratio > 1.5) ratio = 1.5;
    var list = [BG.mainTarget()].concat(BG.frameTargets());
    var chosen = null;
    for(var i=0;i<list.length;i++){
      if(list[i] && !BG.atBottom(list[i])){ chosen = list[i]; break; }
    }
    if(!chosen){ chosen = list[0]; }
    if(!chosen){
      return JSON.stringify({ok:false, err:'no-target', atBottom:true, top:0, height:0, client:0});
    }
    var ch = chosen.clientHeight || window.innerHeight || 600;
    var step = Math.max(40, Math.round(ch * ratio));
    var before = chosen.scrollTop;
    chosen.scrollTop = before + step;
    var after = chosen.scrollTop;
    var all = true;
    for(var k=0;k<list.length;k++){
      if(list[k] && !BG.atBottom(list[k])){ all = false; break; }
    }
    return JSON.stringify({
      ok:true,
      moved: (after > before + 1),
      top: after,
      height: chosen.scrollHeight,
      client: chosen.clientHeight,
      atBottom: all,
      targets: list.length
    });
  }catch(e){
    return JSON.stringify({ok:false, err:String(e), atBottom:true, top:0, height:0, client:0});
  }
})();
"""

    /** 스크롤을 맨 위로. (새 라운드 시작 전 초기화용) */
    fun scrollTop(): String = """
(function(){
  try{
    $COMMON
    var list = [BG.mainTarget()].concat(BG.frameTargets());
    for(var i=0;i<list.length;i++){
      try{ list[i].scrollTop = 0; }catch(e){}
    }
    return JSON.stringify({ok:true});
  }catch(e){
    return JSON.stringify({ok:false, err:String(e)});
  }
})();
"""

    /**
     * 발견 시 처리: 첫 매칭 요소로 스크롤 + 노란 테두리 하이라이트 + 상단 고정 빨간 배너 삽입.
     * (WebView 안쪽 표시용. 오버레이 배너와는 별개다.)
     */
    fun highlightAndBanner(texts: List<String>, timeText: String): String = """
(function(){
  try{
    $COMMON
    var raw = ${qArray(texts)};
    var ts = ${q(timeText)};
    var hit = 0;
    for(var i=0;i<raw.length;i++){
      var n = BG.norm(raw[i]);
      if(!n) continue;
      var el = BG.findAnyDoc(n);
      if(!el) continue;
      if(hit === 0){
        try{ el.scrollIntoView({block:'center'}); }catch(e){ try{ el.scrollIntoView(); }catch(e2){} }
      }
      hit++;
      try{
        el.style.outline = '3px solid #FFEB3B';
        el.style.outlineOffset = '2px';
        el.style.boxShadow = '0 0 0 3px #FFEB3B';
        el.style.backgroundColor = 'rgba(255,235,59,0.30)';
      }catch(e){}
    }
    var old = document.getElementById('__bg_banner');
    if(old && old.parentNode){ old.parentNode.removeChild(old); }
    var b = document.createElement('div');
    b.id = '__bg_banner';
    b.setAttribute('style',
      'position:fixed;top:0;left:0;right:0;z-index:2147483647;' +
      'background:#d32f2f;color:#ffffff;font-size:15px;font-weight:bold;' +
      'padding:10px 12px;text-align:center;font-family:sans-serif;line-height:1.4;' +
      'box-shadow:0 2px 8px rgba(0,0,0,0.4);');
    b.textContent = '발견: ' + raw.join(', ') + '  (' + ts + ')';
    (document.body || document.documentElement).appendChild(b);
    return JSON.stringify({ok:true, highlighted: hit});
  }catch(e){
    return JSON.stringify({ok:false, err:String(e)});
  }
})();
"""

    /** 배너 제거 (탐색 재개 시). */
    fun clearBanner(): String = """
(function(){
  try{
    var old = document.getElementById('__bg_banner');
    if(old && old.parentNode){ old.parentNode.removeChild(old); }
    return JSON.stringify({ok:true});
  }catch(e){
    return JSON.stringify({ok:false});
  }
})();
"""
}
