(function () {
  var data = { apps: [], host: {}, history: [], processes: [], journal: [] };
  var tab = "dash";
  var skApp = "core";
  var socketGen = 0;
  var sockets = [];
  var selected = null;
  var channel = null;

  function $(id) { return document.getElementById(id); }
  function toast(msg) {
    var el = $("toast");
    el.textContent = msg;
    el.style.display = "block";
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { el.style.display = "none"; }, 3200);
  }
  function bytes(n) {
    if (n == null || isNaN(n)) return "-";
    var u = ["B", "KB", "MB", "GB", "TB"];
    var v = Number(n);
    var i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return v.toFixed(i === 0 ? 0 : 1) + " " + u[i];
  }
  function pct(used, total) {
    if (!total) return 0;
    return Math.max(0, Math.min(100, used / total * 100));
  }
  function dur(ms) {
    if (ms == null) return "-";
    var s = Math.floor(Number(ms) / 1000);
    var d = Math.floor(s / 86400);
    s %= 86400;
    var h = Math.floor(s / 3600);
    s %= 3600;
    var m = Math.floor(s / 60);
    if (d > 0) return d + "일 " + h + "시간";
    if (h > 0) return h + "시간 " + m + "분";
    return m + "분";
  }
  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  document.querySelectorAll(".tabs button").forEach(function (btn) {
    btn.addEventListener("click", function () {
      tab = btn.getAttribute("data-tab");
      document.querySelectorAll(".tabs button").forEach(function (b) { b.classList.toggle("on", b === btn); });
      ["dash", "apps", "sock", "host", "set"].forEach(function (id) {
        $(id).classList.toggle("hidden", id !== tab);
      });
      if (tab === "sock") ensurePick();
      if (tab === "set") loadSettings();
    });
  });

  function paint() {
    var host = data.host || {};
    var cpu = Number(host.cpu || 0);
    $("cpu").textContent = cpu.toFixed(1) + "%";
    $("cpuBar").style.width = cpu + "%";
    var memPct = pct(host.memUsed, host.memTotal);
    $("mem").textContent = memPct.toFixed(0) + "%";
    $("memSub").textContent = bytes(host.memUsed) + " / " + bytes(host.memTotal);
    $("memBar").style.width = memPct + "%";
    var disks = host.disks || [];
    var used = 0, total = 0;
    disks.forEach(function (d) { used += Number(d.used || 0); total += Number(d.total || 0); });
    $("disk").textContent = total ? pct(used, total).toFixed(0) + "%" : "-";
    $("diskSub").textContent = disks.map(function (d) { return d.path.trim(); }).join("  ");
    var up = (data.apps || []).filter(function (a) { return a.state === "UP"; }).length;
    $("upCount").textContent = up + " / " + (data.apps || []).length;
    $("upSub").textContent = "프로세스와 HTTP 응답이 같이 있으면 UP";
    drawSpark();
    paintCards();
    paintApps();
    paintHost();
    ensurePick();
  }

  function drawSpark() {
    var c = $("spark");
    var w = c.clientWidth || 240;
    var h = 72;
    c.width = w;
    c.height = h;
    var g = c.getContext("2d");
    g.clearRect(0, 0, w, h);
    var hist = data.history || [];
    if (hist.length < 2) return;
    g.beginPath();
    g.strokeStyle = "#e4b15a";
    g.lineWidth = 2;
    hist.forEach(function (p, i) {
      var x = i / (hist.length - 1) * (w - 4) + 2;
      var y = h - 4 - (Number(p.cpu) / 100) * (h - 8);
      if (i === 0) g.moveTo(x, y); else g.lineTo(x, y);
    });
    g.stroke();
  }

  function paintCards() {
    $("cards").innerHTML = (data.apps || []).map(function (a) {
      var j = a.jmx || {};
      var jmxOn = j.up === true;
      var noJmx = a.state === "UP" ? "JMX 없음" : "-";
      var heap = jmxOn ? bytes(j.heapUsed) : noJmx;
      var threads = jmxOn ? j.threads : noJmx;
      var procCpu = jmxOn && j.procCpu != null ? Number(j.procCpu).toFixed(1) + "%" : noJmx;
      return '<article class="card"><h3><i class="led ' + esc(a.state.toLowerCase()) + '"></i>' + esc(a.name) + '</h3>'
        + '<div class="meta">'
        + '<span>상태 <b>' + esc(a.state) + '</b></span>'
        + '<span>PID <b>' + esc(a.pid || "-") + '</b></span>'
        + '<span>Heap <b>' + heap + '</b></span>'
        + '<span>Thread <b>' + esc(threads) + '</b></span>'
        + '<span>기동 <b>' + (jmxOn ? dur(j.uptime) : noJmx) + '</b></span>'
        + '<span>WS <b>' + (a.wsUp ? "열림" : "닫힘") + '</b></span>'
        + '<span>HTTP <b>' + httpLabel(a) + ' ' + a.httpPort + '</b></span>'
        + '<span>프로세스 CPU <b>' + procCpu + '</b></span>'
        + '</div></article>';
    }).join("");
  }

  function paintApps() {
    $("appRows").innerHTML = (data.apps || []).map(function (a) {
      var j = a.jmx || {};
      var jmxOn = j.up === true;
      var noJmx = a.state === "UP" ? "JMX 없음" : "-";
      return '<tr><td>' + esc(a.name) + '</td><td><i class="led ' + esc(String(a.state).toLowerCase()) + '"></i> ' + esc(a.state) + '</td>'
        + '<td>' + esc(a.pid || "-") + '</td>'
        + '<td>' + (jmxOn ? bytes(j.heapUsed) + ' / ' + bytes(j.heapMax) : noJmx) + '</td>'
        + '<td>' + (jmxOn ? esc(j.threads) : noJmx) + '</td>'
        + '<td>' + (jmxOn && j.procCpu != null ? Number(j.procCpu).toFixed(1) + "%" : noJmx) + '</td>'
        + '<td>http ' + a.httpPort + ' ' + httpLabel(a) + '<br>ws ' + a.wsPort + '<br>jmx ' + a.jmxPort
        + '<br><a href="http://127.0.0.1:' + a.httpPort + '/" target="_blank">기존 소켓화면</a></td>'
        + '<td><button class="btn primary" data-act="start" data-id="' + a.id + '">시작</button> '
        + '<button class="btn danger" data-act="stop" data-id="' + a.id + '">정지</button> '
        + '<button class="btn" data-act="restart" data-id="' + a.id + '">재시작</button> '
        + '<button class="btn" data-act="deploy" data-id="' + a.id + '">JAR 옮기기</button></td></tr>';
    }).join("");
  }

  function paintHost() {
    var disks = (data.host && data.host.disks) || [];
    $("diskList").innerHTML = disks.map(function (d) {
      var p = pct(d.used, d.total);
      return '<div style="margin-bottom:10px"><div class="row"><b>' + esc(d.path) + '</b><span class="k">'
        + bytes(d.used) + ' / ' + bytes(d.total) + '</span></div><div class="bar"><i style="width:' + p + '%"></i></div></div>';
    }).join("");
    $("procRows").innerHTML = (data.processes || []).map(function (p) {
      return '<tr><td>' + p.pid + '</td><td>' + esc(p.name) + '</td><td>' + bytes(p.workingSet) + '</td><td>' + esc(p.command) + '</td></tr>';
    }).join("");
    $("journal").textContent = (data.journal || []).join("\n") || "기록 없음";
  }

  function ensurePick() {
    var box = $("appPick");
    if (box.childNodes.length) return;
    box.innerHTML = (data.apps || []).map(function (a) {
      return '<button data-app="' + a.id + '"' + (a.id === skApp ? ' class="on"' : '') + '>' + esc(a.name) + '</button>';
    }).join("");
  }

  $("appPick").addEventListener("click", function (e) {
    var btn = e.target.closest("button");
    if (!btn) return;
    socketGen++;
    skApp = btn.getAttribute("data-app");
    $("appPick").querySelectorAll("button").forEach(function (b) { b.classList.toggle("on", b === btn); });
    $("skFilter").value = "";
    sockets = [];
    selected = null;
    channel = null;
    renderSockets();
    $("chRows").innerHTML = "";
    $("skStatus").textContent = "";
  });

  $("appRows").addEventListener("click", function (e) {
    var btn = e.target.closest("button");
    if (!btn) return;
    var id = btn.getAttribute("data-id");
    var act = btn.getAttribute("data-act");
    var app = (data.apps || []).filter(function (a) { return a.id === id; })[0];
    var name = app ? app.name : id;
    var word = act === "start" ? "시작" : act === "stop" ? "정지" : act === "deploy" ? "JAR 옮기기" : "재시작";
    var ask = name + " 을(를) " + word + " 합니까?";
    if (act === "restart") {
      ask = name + " 을(를) 종료하고 10초 뒤 다시 시작합니다. HTTP 응답이 올 때까지 기다립니다.";
    }
    if (act === "deploy") {
      ask = name + " target JAR를 " + (data.jarDir || "설정에 적은 JAR 폴더") + " 로 옮깁니까?";
    }
    if (!confirm(ask)) return;
    if (act === "restart") toast(name + " 재시작 중. HTTP 응답까지 확인합니다");
    if (act === "start") toast(name + " 기동 중. HTTP 응답까지 확인합니다");
    actApp(id, act);
  });

  function httpLabel(a) {
    if (a.httpAlive) return "응답";
    if (a.httpUp) return "무응답";
    return "닫힘";
  }

  function actApp(id, act) {
    fetch("/api/apps/" + encodeURIComponent(id) + "/" + act, { method: "POST" })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        toast(j.message || j.ok);
        load();
      })
      .catch(function (err) { toast(String(err)); });
  }

  function callSocket(execute, work, app) {
    var useApp = app || skApp;
    $("skStatus").textContent = execute + " 요청 중";
    return fetch("/api/socket", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ app: useApp, execute: execute, work: work || {} })
    }).then(function (r) { return r.json(); }).then(function (j) {
      if (!j.ok) throw new Error(j.message || "실패");
      $("skStatus").textContent = useApp + " " + execute + " " + j.elapsedMs + "ms";
      return unwrap(j.text);
    });
  }

  function unwrap(text) {
    var obj = JSON.parse(text);
    var list = obj.DATA_LIST;
    if (typeof list === "string" && list.length) {
      try { list = JSON.parse(list); } catch (e) {}
    }
    return { raw: obj, list: list, work: obj.WORK_VALUE };
  }

  function linkOf(s) {
    var active = Number(s.activeChannels || 0);
    var kind = String(s.SK_CONN_TYPE || "").toUpperCase();
    var stat = String(s.SK_STAT || "").toUpperCase();
    if (s.channelChecked == null) return { text: "확인중", cls: "" };
    if (s.channelChecked === false) return { text: "확인실패", cls: "degraded" };
    if (kind === "CLIENT") {
      if (active > 0) return { text: "연결", cls: "up" };
      if (stat === "RUN") return { text: "끊김", cls: "down" };
      return { text: "정지", cls: "down" };
    }
    if (active > 0) return { text: "연결 " + active, cls: "up" };
    if (stat === "RUN") return { text: "대기", cls: "degraded" };
    return { text: "정지", cls: "down" };
  }

  function summarizeSockets(list) {
    var client = 0, clientOk = 0, clientDown = 0, server = 0, serverOk = 0, serverWait = 0;
    list.forEach(function (s) {
      var kind = String(s.SK_CONN_TYPE || "").toUpperCase();
      var link = linkOf(s).text;
      if (kind === "CLIENT") {
        client++;
        if (link === "연결") clientOk++;
        else if (link === "끊김") clientDown++;
      } else {
        server++;
        if (link.indexOf("연결") === 0) serverOk++;
        else if (link === "대기") serverWait++;
      }
    });
    return "클라이언트 " + clientOk + "/" + client + " 연결, 끊김 " + clientDown
      + " · 서버 " + serverOk + "/" + server + " 연결, 대기 " + serverWait;
  }

  function renderSockets() {
    var q = ($("skFilter").value || "").toLowerCase();
    var rows = sockets.filter(function (s) {
      var link = linkOf(s).text;
      var blob = (s.SK_ID || "") + " " + (s.PARENT_SK_ID || "") + " " + (s.SK_IP || "") + " " + link + " " + (s.SK_STAT || "");
      return blob.toLowerCase().indexOf(q) >= 0;
    });
    if (!rows.length) {
      $("skRows").innerHTML = sockets.length
        ? "<tr><td colspan='5'>필터에 맞는 소켓 없음</td></tr>"
        : "";
      $("skRows")._rows = [];
      return;
    }
    $("skRows").innerHTML = rows.map(function (s, i) {
      var on = selected && selected.SK_ID === s.SK_ID && selected.PARENT_SK_ID === s.PARENT_SK_ID;
      var link = linkOf(s);
      return '<tr class="' + (on ? "sel" : "") + '" data-i="' + i + '"><td>' + esc(s.SK_STAT || "-") + '</td>'
        + '<td><i class="led ' + link.cls + '"></i> ' + esc(link.text) + '</td>'
        + '<td>' + esc(s.SK_ID) + '<br><span class="k">' + esc(s.PARENT_SK_ID) + '</span></td>'
        + '<td>' + esc(s.SK_CONN_TYPE) + ' / ' + esc(s.SK_TYPE) + '<br>' + esc(s.SK_CLIENT_TYPE || "") + '</td>'
        + '<td>' + esc(s.SK_IP || "") + ':' + esc(s.SK_PORT) + '</td></tr>';
    }).join("");
    $("skRows")._rows = rows;
  }

  function renderChannelRows(list) {
    $("chRows").innerHTML = list.map(function (c, i) {
      return '<tr data-i="' + i + '"><td>' + esc(c.CHANNEL_REMOTE || c.CHANNEL_INFO) + '</td><td>'
        + esc(c.CHANNEL_ACTIVE_DATE || "") + '</td><td>' + esc(c.CHANNEL_STAT || "") + '</td></tr>';
    }).join("") || "<tr><td colspan='3'>채널 없음</td></tr>";
    $("chRows")._rows = list;
  }

  function channelsOf(s, app) {
    return callSocket("MONITER_CHANNEL_LIST", {
      PARENT_SK_ID: s.PARENT_SK_ID || "",
      SK_ID: s.SK_ID || ""
    }, app).then(function (res) {
      var list = Array.isArray(res.list) ? res.list : [];
      s.channels = list;
      s.activeChannels = list.filter(function (c) { return String(c.CHANNEL_STAT) === "ACTIVE"; }).length;
      s.channelChecked = true;
      return list;
    });
  }

  function fillChannels(list, gen, app) {
    var i = 0;
    function step() {
      if (gen !== socketGen) return Promise.resolve();
      if (i >= list.length) return Promise.resolve();
      var s = list[i++];
      $("skStatus").textContent = "채널 확인 " + i + "/" + list.length;
      return channelsOf(s, app).catch(function () {
        s.channels = [];
        s.activeChannels = 0;
        s.channelChecked = false;
      }).then(function () {
        if (gen !== socketGen) return;
        renderSockets();
        return step();
      });
    }
    return step();
  }

  $("skRows").addEventListener("click", function (e) {
    var tr = e.target.closest("tr");
    if (!tr) return;
    selected = $("skRows")._rows[Number(tr.getAttribute("data-i"))];
    channel = null;
    renderSockets();
    renderChannelRows(selected.channels || []);
    $("msgBox").textContent = selected.SK_ID + " · 기동 " + (selected.SK_STAT || "-") + " · " + linkOf(selected).text;
  });

  $("btnLoadSk").addEventListener("click", function () {
    var gen = ++socketGen;
    var app = skApp;
    callSocket("MONITER_SOCKET_LIST", {
      PARENT_SK_ID: "",
      SK_ID: ""
    }, app).then(function (res) {
      if (gen !== socketGen) return;
      sockets = Array.isArray(res.list) ? res.list : [];
      selected = null;
      channel = null;
      renderSockets();
      $("chRows").innerHTML = "";
      return fillChannels(sockets, gen, app);
    }).then(function () {
      if (gen !== socketGen) return;
      $("skStatus").textContent = summarizeSockets(sockets);
    }).catch(function (err) {
      if (gen !== socketGen) return;
      toast(String(err.message || err));
      $("skStatus").textContent = String(err.message || err);
    });
  });
  $("skFilter").addEventListener("input", renderSockets);

  function needSocket() {
    if (!selected) { toast("소켓을 선택하세요"); return false; }
    return true;
  }

  $("btnSkStart").addEventListener("click", function () {
    if (!needSocket()) return;
    var sub = selected.PARENT_SK_ID && selected.PARENT_SK_ID !== selected.SK_ID;
    var execute = sub ? "SUB_START" : "SK_START";
    if (!confirm(selected.SK_ID + " " + execute + " ?")) return;
    var work = sub
      ? { PARENT_SK_ID: selected.PARENT_SK_ID, SK_ID: selected.SK_ID }
      : { PARENT_SK_ID: selected.SK_ID, SK_ID: selected.SK_ID, PKG_ID: selected.PKG_ID || "" };
    callSocket(execute, work).then(function () { toast("요청했습니다"); $("btnLoadSk").click(); })
      .catch(function (err) { toast(String(err.message || err)); });
  });
  $("btnSkStop").addEventListener("click", function () {
    if (!needSocket()) return;
    var sub = selected.PARENT_SK_ID && selected.PARENT_SK_ID !== selected.SK_ID;
    var execute = sub ? "SUB_STOP" : "SK_STOP";
    if (!confirm(selected.SK_ID + " " + execute + " ?")) return;
    var work = sub
      ? { PARENT_SK_ID: selected.PARENT_SK_ID, SK_ID: selected.SK_ID }
      : { PARENT_SK_ID: selected.SK_ID, SK_ID: selected.SK_ID };
    callSocket(execute, work).then(function () { toast("요청했습니다"); $("btnLoadSk").click(); })
      .catch(function (err) { toast(String(err.message || err)); });
  });
  $("btnPing").addEventListener("click", function () {
    if (!needSocket()) return;
    callSocket("MONITER_CHECK_PING_SK", {
      PARENT_SK_ID: selected.PARENT_SK_ID,
      SK_ID: selected.SK_ID,
      SK_IP: selected.SK_IP || ""
    }).then(function (res) {
      $("msgBox").textContent = JSON.stringify(res.list, null, 2);
    }).catch(function (err) { toast(String(err.message || err)); });
  });
  $("btnRerun").addEventListener("click", function () {
    if (!needSocket()) return;
    if (String(selected.SK_CONN_TYPE || "").toUpperCase() !== "CLIENT") {
      toast("CLIENT 소켓만 재접속할 수 있습니다");
      return;
    }
    if (!confirm(selected.SK_ID + " 채널을 닫고 재접속합니까?")) return;
    var target = selected;
    callSocket("RERUN_CLIENT", {
      PARENT_SK_ID: target.PARENT_SK_ID,
      SK_ID: target.SK_ID
    }).then(function () {
      $("skStatus").textContent = target.SK_ID + " 재접속 확인 중";
      return waitActive(target, 10);
    }).then(function (ok) {
      renderSockets();
      renderChannelRows(target.channels || []);
      $("skStatus").textContent = summarizeSockets(sockets);
      toast(ok ? target.SK_ID + " 채널이 다시 붙었습니다" : target.SK_ID + " 재접속 후에도 활성 채널이 없습니다");
    }).catch(function (err) { toast(String(err.message || err)); });
  });
  $("btnChan").addEventListener("click", function () {
    if (!needSocket()) return;
    callSocket("MONITER_CHANNEL_LIST", {
      PARENT_SK_ID: selected.PARENT_SK_ID,
      SK_ID: selected.SK_ID
    }).then(function (res) {
      var list = Array.isArray(res.list) ? res.list : [];
      selected.channels = list;
      selected.activeChannels = list.filter(function (c) { return String(c.CHANNEL_STAT) === "ACTIVE"; }).length;
      selected.channelChecked = true;
      renderSockets();
      renderChannelRows(list);
      $("skStatus").textContent = summarizeSockets(sockets);
    }).catch(function (err) { toast(String(err.message || err)); });
  });
  $("chRows").addEventListener("click", function (e) {
    var tr = e.target.closest("tr");
    if (!tr || !$("chRows")._rows) return;
    var idx = tr.getAttribute("data-i");
    if (idx == null) return;
    channel = $("chRows")._rows[Number(idx)];
    $("msgBox").textContent = channel ? (channel.CHANNEL_INFO || "") : "";
  });
  function waitActive(s, left) {
    return channelsOf(s).then(function () {
      if (s.activeChannels > 0) return true;
      if (left <= 1) return false;
      return new Promise(function (resolve) { setTimeout(resolve, 1000); })
        .then(function () { return waitActive(s, left - 1); });
    });
  }

  function history(type) {
    if (!selected || !channel) { toast("채널을 선택하세요"); return; }
    callSocket(type === "READ" ? "MONITER_CHANNEL_READ_LIST" : "MONITER_CHANNEL_WRITE_LIST", {
      PARENT_SK_ID: selected.PARENT_SK_ID,
      SK_ID: selected.SK_ID,
      CHANNEL_INFO: channel.CHANNEL_INFO,
      HISTORY_TYPE: type
    }).then(function (res) {
      var list = Array.isArray(res.list) ? res.list : [];
      if (!list.length) { $("msgBox").textContent = "이력 없음"; return; }
      $("msgBox").textContent = list.slice(0, 30).map(function (m) {
        return (m.TIME || "") + "  " + (m.MSG_ID || "") + "\n" + (m.MSG || m.MSG_JSON || "");
      }).join("\n\n--------------------\n\n");
    }).catch(function (err) { toast(String(err.message || err)); });
  }
  $("btnRead").addEventListener("click", function () { history("READ"); });
  $("btnWrite").addEventListener("click", function () { history("WRITE"); });
  $("btnThreads").addEventListener("click", function () {
    callSocket("MONITER_THREAD_LIST", {}).then(function (res) {
      var list = Array.isArray(res.list) ? res.list : [];
      var lines = list.slice(0, 80).map(function (t) {
        return (t.NAME || "-") + "  " + (t.STATE || "") + "  #" + (t.ID == null ? "" : t.ID);
      });
      var more = list.length > 80 ? "\n... " + (list.length - 80) + "개 생략" : "";
      $("msgBox").textContent = "스레드 " + list.length + "\n" + lines.join("\n") + more;
    }).catch(function (err) { toast(String(err.message || err)); });
  });

  var setRows = [];

  function blankApp() {
    return { id: "", name: "", httpPort: "", wsPort: "", jmxPort: "", processName: "", batPath: "", commandHint: "", jarSource: "" };
  }

  function renderSettings() {
    $("setApps").innerHTML = setRows.map(function (a, i) {
      function field(key, label) {
        return '<label class="k">' + label
          + '<input data-k="' + key + '" data-i="' + i + '" value="' + esc(a[key]).replace(/"/g, "&quot;") + '"></label>';
      }
      return '<div class="card" style="margin-top:10px" data-card="' + i + '">'
        + '<div class="row"><b>' + esc(a.name || a.id || "새 앱") + '</b>'
        + '<button class="btn danger" data-del="' + i + '">삭제</button></div>'
        + '<div class="set-fields">'
        + field("id", "ID") + field("name", "표시 이름")
        + field("httpPort", "HTTP") + field("wsPort", "WS") + field("jmxPort", "JMX")
        + field("processName", "프로세스 이름")
        + field("batPath", "기동 bat")
        + field("commandHint", "커맨드라인 식별자")
        + field("jarSource", "개발 target JAR")
        + '</div></div>';
    }).join("");
  }

  function readSettingsForm() {
    var rows = setRows.map(function (a) { return { id: a.id, name: a.name, httpPort: a.httpPort, wsPort: a.wsPort, jmxPort: a.jmxPort, processName: a.processName, batPath: a.batPath, commandHint: a.commandHint, jarSource: a.jarSource }; });
    [].slice.call(document.querySelectorAll("#setApps input")).forEach(function (input) {
      var i = Number(input.getAttribute("data-i"));
      var k = input.getAttribute("data-k");
      if (rows[i]) rows[i][k] = input.value;
    });
    return rows;
  }

  function loadSettings() {
    fetch("/api/settings").then(function (r) { return r.json(); }).then(function (j) {
      if (!j.ok) throw new Error(j.message || "설정 조회 실패");
      $("setJarDir").value = j.jarDir || "";
      setRows = j.apps || [];
      renderSettings();
      $("setStatus").textContent = "data/ops.db";
    }).catch(function (err) {
      $("setStatus").textContent = String(err.message || err);
    });
  }

  $("btnAddApp").addEventListener("click", function () {
    setRows = readSettingsForm();
    setRows.push(blankApp());
    renderSettings();
  });
  $("setApps").addEventListener("click", function (e) {
    var btn = e.target.closest("button");
    if (!btn || btn.getAttribute("data-del") == null) return;
    setRows = readSettingsForm();
    setRows.splice(Number(btn.getAttribute("data-del")), 1);
    renderSettings();
  });
  $("btnSaveSet").addEventListener("click", function () {
    var apps = readSettingsForm().map(function (a) {
      return {
        id: a.id, name: a.name,
        httpPort: Number(a.httpPort), wsPort: Number(a.wsPort), jmxPort: Number(a.jmxPort),
        processName: a.processName, batPath: a.batPath, commandHint: a.commandHint, jarSource: a.jarSource
      };
    });
    fetch("/api/settings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jarDir: $("setJarDir").value, apps: apps })
    }).then(function (r) { return r.json(); }).then(function (j) {
      if (!j.ok) throw new Error(j.message || "저장 실패");
      toast(j.message || "저장했습니다");
      load();
      loadSettings();
    }).catch(function (err) { toast(String(err.message || err)); });
  });

  function load() {
    fetch("/api/overview").then(function (r) { return r.json(); }).then(function (j) {
      if (!j.ok) throw new Error(j.message || "overview 실패");
      data = j;
      paint();
    }).catch(function (err) {
      $("upSub").textContent = String(err.message || err);
    });
  }

  setInterval(function () {
    var d = new Date();
    $("clock").textContent = d.toLocaleString();
  }, 1000);
  load();
  setInterval(load, 4000);
})();
