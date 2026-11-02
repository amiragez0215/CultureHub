import { streamGuide } from "./sse.js";
import {
  createApp,
  ref,
  customRef,
  computed,
  onMounted,
  onUnmounted,
  nextTick,
  watch,
} from "vue/dist/vue.esm-bundler.js";
import QRCode from "qrcode";
import {
  label,
  errorMessage,
  displayResult,
  notificationText,
} from "./locale.js";
import "./style.css";
import "./interaction.css";
import template from "./app-template.html?raw";
import DataView from "./DataView.js";
export const App = {
  components: { DataView },
  setup() {
    function timedMessage(duration) {
      let value = "", timer;
      onUnmounted(() => clearTimeout(timer));
      return customRef((track, trigger) => ({
        get() { track(); return value; },
        set(message) {
          clearTimeout(timer);
          value = message;
          trigger();
          // Restart even for repeated identical messages, so each operation gets a full display period.
          if (message) timer = setTimeout(() => { value = ""; trigger(); }, duration);
        },
      }));
    }
    const token = ref(sessionStorage.getItem("token") || ""),
      me = ref(null),
      page = ref("activities"),
      rows = ref([]),
      sessions = ref([]),
      selected = ref(null),
      error = timedMessage(6000),
      busy = ref(false),
      result = ref(null),
      qr = ref(""),
      q = ref(""),
      category = ref(""),
      managementStatus = ref("ACTIVE"),
      venueFilter = ref(0),
      venueCategory = ref(""),
      venueQuery = ref(""),
      publicVenues = ref([]),
      sessionFields = ref({}),
      grantUserId = ref(0),
      grantVenueIds = ref([]),
      grantOriginalIds = ref([]),
      grantReady = ref(false),
      grantUserQuery = ref(""),
      grantVenueQuery = ref(""),
      pageNo = ref(1),
      auth = ref({ username: "", password: "" }),
      form = ref({}),
      conversation = ref(""),
      guideProposal = ref(null),
      guideServerOffset = ref(0),
      messages = ref([]),
      question = ref(""),
      venues = ref([]),
      total = ref(0),
      toast = timedMessage(3000),
      venueInfo = ref(null),
      ticket = ref(null),
      detailOrigin = ref("activities"),
      resultTitle = ref(""),
      clock = ref(Date.now());
    let pending = 0,
      routing = false,
      viewEpoch = 0;
    let grantEpoch = 0;
    let guideOwner = "", proposalPoll, proposalPollCount = 0, guideAbort;
    const guideStreaming=ref(false),guideProgress=ref("");
    function stopGuide(){guideAbort?.abort();}
    const grantUser = computed(() => rows.value.find(u => u.id === grantUserId.value));
    const grantUsers = computed(() => rows.value.filter(u => u.id === grantUserId.value ||
      !grantUserQuery.value.trim() || (u.username + " " + u.nickname).includes(grantUserQuery.value.trim())));
    const grantVenues = computed(() => venues.value.filter(v => !grantVenueQuery.value.trim() ||
      (v.name + " " + v.address).includes(grantVenueQuery.value.trim())));
    const grantChanged = computed(() => [...grantVenueIds.value].sort((a,b)=>a-b).join(",") !==
      [...grantOriginalIds.value].sort((a,b)=>a-b).join(","));
    const pages = { activities: 1, mine: 1, manage: 1 };
    const categories = [
      "HERITAGE",
      "LECTURE",
      "ART",
      "CULTURE",
      "FAMILY",
      "TRADITION",
    ];
    const audiences = ["ALL", "FAMILY", "CHILDREN", "YOUTH", "ADULT", "SENIOR"];
    const venueCategories = ["MUSEUM", "HERITAGE_HALL", "ART_GALLERY", "READING_ROOM",
      "CULTURE_CENTER", "FOLK_HALL", "SCIENCE_CENTER", "LIBRARY"];
    const sessionTimeKeys = ["registration_start_at", "registration_end_at", "start_at",
      "end_at", "checkin_open_at", "checkin_close_at"];
    const pageCount = computed(() => Math.ceil(total.value / 20));
    const visibleVenues = computed(() => rows.value.filter(v =>
      (!venueCategory.value || v.category === venueCategory.value) &&
      (!venueQuery.value.trim() || (v.name + " " + v.address).includes(venueQuery.value.trim()))));
    const meta = {
      activities: ["探索活动", "发现身边的公共文化活动", "活动服务"],
      venuePublic: ["文化场馆", "了解开放时间、场馆地址和参与须知", "场馆服务"],
      mine: ["我的参与", "预约、候补、电子凭证与参与评价", "个人中心"],
      notifications: [
        "站内通知",
        "通知记录发生时的事实，资格以实时查询为准",
        "个人中心",
      ],
      guide: ["智能导览", "了解场馆规则，查询与发现活动", "智能服务"],
      login: ["登录与注册", "登录后预约活动、查看凭证和评价", "账号服务"],
      manage: ["活动与场次管理", "维护活动内容、场次和参与记录", "管理工作台"],
      venues: ["场馆管理", "查看授权场馆，平台管理员可维护资料", "管理工作台"],
      grants: ["管理员授权", "仅平台管理员可调整场馆授权", "管理工作台"],
      check: [
        "现场签到核销",
        "确认正确场次后，输入电子凭证进行核销",
        "管理工作台",
      ],
      stats: [
        "运营报表",
        "查看实时签到进度与已结束场次的最终指标",
        "管理工作台",
      ],
      plan: ["智能活动策划", "生成可编辑草稿，由管理员审核发布", "管理工作台"],
      report: ["智能运营复盘", "根据真实统计与评价进行分析", "管理工作台"],
      activityDetail: [
        "活动详情与场次",
        "查看活动安排，选择合适的场次",
        "探索活动",
      ],
      adminDetail: [
        "场次管理",
        "查看参与记录、核销统计和用户评价",
        "活动与场次管理",
      ],
      venueDetail: ["场馆参观详情", "查看场馆位置与开放信息", "文化场馆"],
      credential: ["电子签到凭证", "到场后出示二维码或核销码", "我的参与"],
      review: ["提交或修改评价", "请根据实际参与体验填写评价", "我的参与"],
      editActivity: [
        "编辑活动",
        "保存草稿后，通过发布操作对外开放",
        "活动与场次管理",
      ],
      editSession: [
        "编辑场次",
        "已有参与历史的场次不能修改关键安排",
        "场次管理",
      ],
      editVenue: ["编辑场馆", "维护公开的场馆基础资料", "场馆管理"],
    };
    const pageMeta = computed(
      () => meta[page.value] || ["文化有约", "", "公共文化服务"],
    );
    const section = computed(() =>
      ["adminDetail", "editActivity", "editSession"].includes(page.value)
        ? "manage"
        : page.value === "editVenue"
          ? "venues"
          : page.value === "activityDetail"
            ? "activities"
            : page.value === "venueDetail"
              ? "venuePublic"
              : ["credential", "review"].includes(page.value)
                ? "mine"
                : page.value,
    );
    const platform = computed(() =>
      me.value?.roles?.includes("PLATFORM_ADMIN"),
    );
    const stamp = (x) => (x ? Date.parse(String(x).replace(" ", "T")) : NaN);
    const date = (x) =>
      x
        ? String(x)
            .replace("T", " ")
            .replace(/\.\d+(?:Z)?$/, "")
            .slice(0, 16)
        : "待定";
    const focusView = () =>
      nextTick(() => {
        window.scrollTo({ top: 0, behavior: "auto" });
        document.querySelector("#page-title")?.focus({ preventScroll: true });
      });
    function routeFor(p, id) {
      return "#/" + p + (id ? "/" + id : "");
    }
    function setView(p, id) {
      viewEpoch++;
      const parent = location.hash || "#/activities";
      page.value = p;
      error.value = "";
      result.value = null;
      qr.value = "";
      if (!routing && location.hash !== routeFor(p, id))
        history.pushState({ culturehub: true, parent }, "", routeFor(p, id));
      focusView();
    }
    async function back() {
      if (history.state?.culturehub && history.state?.parent) {
        history.back();
        return;
      }
      await nav(
        ["credential", "review"].includes(page.value)
          ? "mine"
          : page.value === "venueDetail"
            ? "venuePublic"
            : ["adminDetail", "editActivity", "editSession"].includes(
                  page.value,
                )
              ? "manage"
              : page.value === "editVenue"
                ? "venues"
                : "activities",
      );
    }
    async function openRoute() {
      const parts = location.hash.replace(/^#\//, "").split("/");
      const p = parts[0] || "activities";
      routing = true;
      try {
        if (p === "activityDetail" || p === "adminDetail") {
          await run(async () => {
            let row =
              p === "activityDetail"
                ? await api("/activities/" + Number(parts[1]))
                : JSON.parse(
                    sessionStorage.getItem("activity:" + parts[1]) || "null",
                  );
            if (!row) {
              await nav("manage");
              return;
            }
            await detail(row, p === "adminDetail");
          });
        } else if (p === "venueDetail") await showVenue(Number(parts[1]));
        else if (p === "credential") await credential(Number(parts[1]));
        else if (p === "review") {
          const row = JSON.parse(
            sessionStorage.getItem("participation:" + parts[1]) || "null",
          );
          if (row) await review(row);
          else await nav("mine");
        } else if (meta[p] && !p.startsWith("edit")) await nav(p);
        else await nav("manage");
      } finally {
        routing = false;
      }
    }
    function resultOpen(title, value) {
      resultTitle.value = title;
      result.value = value;
      nextTick(() => {
        document
          .querySelector("#operation-result")
          ?.scrollIntoView({ behavior: "smooth", block: "start" });
        document
          .querySelector("#operation-result")
          ?.focus({ preventScroll: true });
      });
    }
    async function showVenue(id) {
      await run(async () => {
        setView("venueDetail", id);
        const epoch = viewEpoch;
        venueInfo.value = null;
        const value = await api("/venues/" + id);
        if (epoch === viewEpoch) venueInfo.value = value;
      });
    }
    async function copyCredential() {
      try {
        await navigator.clipboard.writeText(ticket.value.checkin_token);
        toast.value = "核销码已复制";
      } catch {
        toast.value = "请选中下方核销码手动复制";
      }
    }
    const admin = computed(() =>
      me.value?.roles?.some((r) => r.includes("ADMIN")),
    );
    async function api(path, method = "GET", data) {
      const r = await fetch("/api/v1" + path, {
        method,
        headers: {
          "Content-Type": "application/json",
          ...(token.value ? { Authorization: "Bearer " + token.value } : {}),
        },
        ...(data === undefined ? {} : { body: JSON.stringify(data) }),
      });
      const text = await r.text();
      let v;
      try {
        v = JSON.parse(text);
      } catch {
        v = { message: text || r.statusText };
      }
      if (!r.ok) {
        const problem = Error(errorMessage(v.code || v.errorCode, r.status));
        problem.code = v.code || v.errorCode;
        problem.response = v;
        throw problem;
      }
      return v;
    }
    async function run(fn) {
      error.value = "";
      pending++;
      busy.value = true;
      let epoch = viewEpoch;
      try {
        const task = fn();
        epoch = viewEpoch;
        return await task;
      } catch (e) {
        if (epoch !== viewEpoch) return;
        error.value = /[\u3400-\u9fff]/.test(e.message)
          ? e.message
          : "网络连接异常，请稍后重试";
      } finally {
        pending--;
        busy.value = pending > 0;
      }
    }
    async function login(register = false) {
      await run(async () => {
        if (register) await api("/auth/register", "POST", auth.value);
        const r = await api("/auth/login", "POST", auth.value);
        token.value = r.token;
        sessionStorage.setItem("token", r.token);
        me.value = await api("/auth/me");
        toast.value = register ? "注册成功，已为你登录" : "登录成功";
        await nav("activities");
      });
    }
    function logout() {
      sessionStorage.removeItem("token");
      token.value = "";
      me.value = null;
      nav("login");
    }
    async function nav(p) {
      pageNo.value = pages[p] || 1;
      setView(p);
      const epoch = viewEpoch;
      venueInfo.value = null;
      rows.value = [];
      selected.value = null;
      ticket.value = null;
      await run(async () => {
        let list = [],
          scopedVenues = null,
          count = 0;
        if (p === "grants" && !platform.value)
          throw Error("此页面仅向平台管理员开放");
        if (p === "activities" || p === "manage") {
          const filter =
            "?q=" +
            encodeURIComponent(q.value) +
            "&category=" +
            encodeURIComponent(p === "activities" ? category.value : "") +
            "&venueId=" + Number(venueFilter.value) +
            (p === "manage" ? "&status=" + encodeURIComponent(managementStatus.value) : "");
          const prefix = p === "activities" ? "/activities" : "/admin/activities";
          [list, { total: count }] = await Promise.all([
            api(prefix + filter + "&page=" + pageNo.value),
            api(prefix + "/count" + filter),
          ]);
          if (epoch !== viewEpoch) return;
          const lastPage = Math.max(1, Math.ceil(count / 20));
          if (pageNo.value > lastPage) {
            pages[p] = lastPage;
            await nav(p);
            return;
          }
          if (p === "activities") {
            const available = await api("/venues");
            if (epoch !== viewEpoch) return;
            publicVenues.value = available;
          }
        }
        if (p === "venuePublic") list = await api("/venues");
        if (p === "mine")
          [list, {total: count}] = await Promise.all([
            api("/me/participations?page=" + pageNo.value), api("/me/participations/count")]);
        if (p === "notifications") list = await api("/me/notifications");
        if (p === "venues") {
          list = await api("/admin/me/venues");
          scopedVenues = list;
        }
        if (p === "grants") {
          grantEpoch++;
          grantUserId.value = 0;
          grantVenueIds.value = [];
          grantOriginalIds.value = [];
          grantReady.value = false;
          grantUserQuery.value = "";
          grantVenueQuery.value = "";
          [list, scopedVenues] = await Promise.all([api("/admin/users"), api("/admin/me/venues")]);
        }
        if (["manage", "plan", "stats"].includes(p))
          scopedVenues = await api("/admin/me/venues");
        if (epoch !== viewEpoch) return;
        rows.value = list;
        if (["activities", "manage", "mine"].includes(p)) total.value = count;
        if (scopedVenues) {
          venues.value = scopedVenues;
          if (p === "plan")
            form.value = {
              venue_id: scopedVenues[0]?.id,
              audience: "ALL",
              minutes: 60,
            };
          if (p === "stats")
            form.value = {
              venue_id: scopedVenues[0]?.id,
              from: new Date(Date.now() - 30 * 86400000)
                .toISOString()
                .slice(0, 10),
              to: new Date(Date.now() + 30 * 86400000)
                .toISOString()
                .slice(0, 10),
            };
        }
        if (p === "report") form.value = { report_scope: "session" };
        if (p === "guide" && guideProposal.value?.id) await refreshGuideProposal();
      });
    }
    async function detail(row, management = false) {
      detailOrigin.value = management ? "manage" : "activities";
      sessionStorage.setItem("activity:" + row.id, JSON.stringify(row));
      setView(management ? "adminDetail" : "activityDetail", row.id);
      const epoch = viewEpoch;
      selected.value = null;
      sessions.value = [];
      const [activity, list] = await Promise.all([
        management ? Promise.resolve(row) : api("/activities/" + row.id),
        api(
          (management ? "/admin" : "") + "/activities/" + row.id + "/sessions",
        ),
      ]);
      if (epoch !== viewEpoch) return;
      selected.value = activity;
      sessions.value = list;
    }
    async function book(id, wait = false) {
      await run(async () => {
        const key = "request:" + id + ":" + wait;
        let request = sessionStorage.getItem(key);
        if (!request) {
          request = crypto.randomUUID();
          sessionStorage.setItem(key, request);
        }
        try {
          result.value = await api(
            "/sessions/" + id + (wait ? "/waitlist" : "/reservations"),
            "POST",
            { request_id: request },
          );
          sessionStorage.removeItem(key);
          await nav("mine");
          toast.value = wait
            ? "已加入候补队列，请在我的参与中查看进度"
            : "预约成功，可查看电子签到凭证";
        } catch (e) {
          try {
            result.value = await api(
              "/me/participations/by-request/" + request,
            );
            sessionStorage.removeItem(key);
            await nav("mine");
          } catch {
            throw e;
          }
        }
      });
    }
    async function act(id, action) {
      if (
        action === "cancel" &&
        !window.confirm("确认取消本次资格？重新报名需要重新竞争名额或排队。")
      )
        return;
      await run(async () => {
        await api("/participations/" + id + "/" + action, "POST", {});
        toast.value =
          action === "confirm"
            ? "候补确认成功，已转为正式预约"
            : "操作成功，资格状态已更新";
        await nav("mine");
      });
    }
    async function credential(id) {
      await run(async () => {
        setView("credential", id);
        const epoch = viewEpoch;
        ticket.value = null;
        const value = await api("/participations/" + id + "/credential");
        if (epoch !== viewEpoch) return;
        ticket.value = { ...rows.value.find((r) => r.id === id), ...value };
        if (
          ["RESERVED", "CHECKED_IN"].includes(value.status) &&
          value.checkin_token
        ) {
          const image = await QRCode.toDataURL(value.checkin_token, {
            width: 320,
            margin: 2,
          });
          if (epoch === viewEpoch) qr.value = image;
        }
      });
    }
    function edit(row, type) {
      form.value = { ...row };
      if (type === "editSession") {
        sessionFields.value = Object.fromEntries(sessionTimeKeys.map(k => {
          const value = String(row[k] || "").replace(" ", "T");
          return [k, {date: value.slice(0, 10), time: value.slice(11, 16)}];
        }));
      }
      const legacyAudience = {
        不限人群: "ALL",
        亲子家庭: "FAMILY",
        Family: "FAMILY",
      };
      if (legacyAudience[form.value.audience])
        form.value.audience = legacyAudience[form.value.audience];

      result.value = null;
      setView(type);
    }
    function updateSessionTime(key, part, value) {
      sessionFields.value[key][part] = value;
      const field = sessionFields.value[key];
      form.value[key] = field.date && field.time ? field.date + "T" + field.time : "";
    }
    function updateCapacity(value) {
      form.value.capacity = value === "" ? "" : Number(value);
    }
    function newActivity() {
      edit(
        {
          venue_id: venues.value[0]?.id,
          title: "",
          description: "",
          category: "CULTURE",
          audience: "ALL",
          plan: "",
          notice: "",
          copywriting: "",
        },
        "editActivity",
      );
    }
    async function saveActivity() {
      await run(async () => {
        result.value = await api("/admin/activities", "POST", form.value);
        await nav("manage");
        toast.value = "活动已保存";
      });
    }
    function newSession() {
      const d = new Date(Date.now() + 86400000);
      const iso = (n) =>
        new Date(d.getTime() + n * 60000 + 8 * 3600000)
          .toISOString()
          .slice(0, 16);
      edit(
        {
          activity_id: selected.value.id,
          registration_start_at: new Date(
            Date.now() + 8 * 3600000,
          )
            .toISOString()
            .slice(0, 16),
          registration_end_at: iso(-60),
          start_at: iso(0),
          end_at: iso(60),
          checkin_open_at: iso(-30),
          checkin_close_at: iso(60),
          capacity: 20,
        },
        "editSession",
      );
    }
    async function saveSession() {
      await run(async () => {
        const f = form.value;
        if (!Number.isInteger(f.capacity) || f.capacity < 1 || f.capacity > 2147483647)
          throw Error("预约人数必须为 1 至 2147483647 之间的整数");
        if (sessionTimeKeys.some(k => !f[k] || !Number.isFinite(stamp(f[k]))))
          throw Error("请完整填写六组日期和时间");
        if (!(stamp(f.registration_start_at) < stamp(f.registration_end_at) &&
          stamp(f.registration_end_at) <= stamp(f.start_at) && stamp(f.start_at) < stamp(f.end_at) &&
          stamp(f.checkin_open_at) < stamp(f.checkin_close_at) && stamp(f.checkin_close_at) <= stamp(f.end_at)))
          throw Error("请检查时间安排：报名开始早于截止，报名截止不晚于活动开始；签到开始早于截止，签到截止不晚于活动结束");
        const activity = selected.value;
        await api("/admin/sessions", "POST", form.value);
        await detail(activity, true);
        toast.value = "场次已保存";
      });
    }
    async function manageAction(id, action) {
      if (action === "cancel" && !window.confirm("确认取消该活动及其未开始场次？取消后将下架，历史参与记录会保留，此操作不可恢复。")) return;
      await run(async () => {
        await api("/admin/activities/" + id + "/" + action, "POST", {});
        await nav("manage");
        toast.value = action === "cancel" ? "活动已取消并下架，可通过“已取消”筛选查看历史记录" : "活动已发布";
      });
    }
    async function sessionAction(id, action) {
      await run(async () => {
        if (action === "cancel") {
          if (!confirm("确认取消场次？")) return;
          await api("/admin/sessions/" + id + "/cancel", "POST", {});
        } else
          resultOpen(
            {
              participations: "预约与候补记录",
              statistics: "场次签到统计",
              reviews: "场次用户评价",
            }[action],
            await api("/admin/sessions/" + id + "/" + action),
          );
        if (action === "cancel") {
          toast.value = "场次已取消";
          sessions.value = await api(
            "/admin/activities/" + selected.value.id + "/sessions",
          );
        }
      });
    }
    async function review(row) {
      sessionStorage.setItem("participation:" + row.id, JSON.stringify(row));
      await run(async () => {
        setView("review", row.id);
        const epoch = viewEpoch;
        selected.value = row;
        form.value = { session_id: row.session_id, rating: 5, content: "" };
        const saved = await api("/sessions/" + row.session_id + "/my-review");
        if (epoch === viewEpoch) form.value = { ...form.value, ...saved };
      });
    }
    async function saveReview() {
      await run(async () => {
        await api(
          "/sessions/" + form.value.session_id + "/my-review",
          "PUT",
          form.value,
        );
        await nav("mine");
        toast.value = "评价已保存";
      });
    }
    async function check() {
      await run(
        async () =>
          (result.value = await api("/admin/check-ins", "POST", {
            session_id: Number(form.value.session_id),
            token: form.value.token,
          })),
      );
    }
    async function chat() {
      const owner = guideOwner;
      const text = question.value;
      if (!text) return;
      question.value = "";
      messages.value.push({ role: "你", text });
      messages.value.forEach(m => m.current = false);
      await run(async () => {
        if (!conversation.value) {
          const created = await api("/ai/conversations", "POST", {});
          if (owner !== guideOwner) return;
          conversation.value = created.id;
        }
        const activeConversation = conversation.value;
          const reply={role:"文化助手",text:"",type:"",cards:[],current:false};
          messages.value.push(reply);
          // Use the reactive proxy, otherwise progressive writes would not trigger Vue rendering.
          const live=messages.value[messages.value.length-1];
          const controller=new AbortController();guideAbort=controller;guideStreaming.value=true;
          guideProgress.value="正在连接文化助手…";
          let completed=false;
          let timedOut=false;
          const deadline=setTimeout(()=>{timedOut=true;controller.abort();},100000);
          try {
            await streamGuide("/api/v1/ai/conversations/"+activeConversation+"/messages/stream",token.value,text,controller.signal,(event,value)=>{
              if(owner!==guideOwner||activeConversation!==conversation.value)return;
              if(event==="status")guideProgress.value=({CONNECTED:"已连接，正在理解需求…",IntentExtract:"正在理解需求…",InformationRetrieve:"正在查询活动与公开知识…",ProposalGenerate:"正在整理推荐…",ProposalVerify:"正在核验真实场次…",DecisionGate:"正在生成活动说明…",EXPLANATION_FALLBACK:"说明连接中断，正在展示已核验的真实活动…"})[value.node]||"正在处理…";
              if(event==="delta")live.text+=value.text||"";
              if(event==="proposal")live.cards=value.candidates||[];
              if(event==="error")throw Object.assign(new Error(value.message||"导览暂不可用"),{code:value.code});
              if(event==="done"){
                completed=true;live.text=value.explanation;live.type=value.type;
                live.cards=value.candidates||[];live.current=true;live.sources=value.sources;live.knowledgeNotice=value.knowledgeNotice;
              }
            });
            if(owner!==guideOwner||activeConversation!==conversation.value)return;
            if(guideProposal.value?.status==="PENDING")await refreshGuideProposal();
          } catch(e) {
            if(owner!==guideOwner||activeConversation!==conversation.value)return;
            live.current=false;live.cards=[];live.type="ERROR";
            if(e.name==="AbortError")live.text+=(live.text?"\n":"")+(timedOut?"回答超时，已停止接收；未提交预约，请稍后重试。":"已停止生成，未提交预约。可以重新提问。");
            else {if(e.code==="CONVERSATION_EXPIRED")conversation.value="";live.text+=(live.text?"\n":"")+(e.code?errorMessage(e.code,e.status||503):e.message);throw e;}
          } finally {
            clearTimeout(deadline);
            if(guideAbort===controller){guideAbort=null;guideStreaming.value=false;guideProgress.value="";}
            if(!completed){live.current=false;live.cards=[];}
          }
        nextTick(()=>document.querySelector('.chat-panel article:last-of-type')?.scrollIntoView({behavior:'smooth',block:'start'}));
      });
    }
    function applyGuideProposal(value) {
      guideProposal.value = value;
      if (value.serverTime) guideServerOffset.value = stamp(value.serverTime) - Date.now();
      if (value.status !== "PROCESSING") clearInterval(proposalPoll);
    }
    async function refreshGuideProposal() {
      if (!guideProposal.value?.id) return;
      const id = guideProposal.value.id, owner = guideOwner;
      const value = await api("/ai/booking-proposals/" + id);
      if (owner === guideOwner && id === guideProposal.value?.id) applyGuideProposal(value);
    }
    function pollGuideProposal() {
      clearInterval(proposalPoll); proposalPollCount = 0;
      proposalPoll = setInterval(async () => {
        if (page.value !== "guide" || ++proposalPollCount > 10 || guideProposal.value?.status !== "PROCESSING") { clearInterval(proposalPoll); return; }
        try { await refreshGuideProposal(); } catch { clearInterval(proposalPoll); }
      }, 2000);
    }
    async function selectGuideCandidate(candidate, mode) {
      const owner = guideOwner;
      await run(async () => {
        let value;
        try { value = await api("/ai/conversations/" + conversation.value + "/proposals", "POST", {candidateId: candidate.candidateId, mode}); }
        catch(e) { if(owner===guideOwner&&guideProposal.value?.id)await refreshGuideProposal().catch(()=>{}); throw e; }
        if (owner !== guideOwner) return;
        applyGuideProposal(value);
        nextTick(() => document.querySelector("#guide-proposal")?.scrollIntoView({behavior:"smooth",block:"center"}));
      });
    }
    async function confirmGuideProposal() {
      if (!guideProposal.value || busy.value) return;
      const owner = guideOwner, id = guideProposal.value.id;
      await run(async () => {
        try { const value = await api("/ai/booking-proposals/" + id + "/confirm", "POST", {}); if(owner !== guideOwner || id !== guideProposal.value?.id) return; applyGuideProposal(value); }
        catch (e) {
          if (owner !== guideOwner || id !== guideProposal.value?.id) return;
          if (e.response?.proposalId) applyGuideProposal(e.response);
          else await refreshGuideProposal().catch(() => {});
          if (guideProposal.value?.status !== "SUCCEEDED" && guideProposal.value?.status !== "PROCESSING") throw e;
        }
        if (guideProposal.value.status === "PROCESSING") pollGuideProposal();
      });
    }
    async function rejectGuideProposal() {
      const owner = guideOwner;
      await run(async () => {const value=await api("/ai/booking-proposals/" + guideProposal.value.id + "/reject", "POST", {});if(owner===guideOwner)applyGuideProposal(value);});
    }
    async function newGuideConversation() {
      const owner = guideOwner;
      if (guideProposal.value?.status === "PROCESSING") { toast.value = "请先查询已提交方案的结果"; return; }
      await run(async () => {
        if (guideProposal.value?.status === "PENDING") await api("/ai/booking-proposals/" + guideProposal.value.id + "/reject", "POST", {});
        if(owner!==guideOwner)return;
        const created = await api("/ai/conversations", "POST", {});
        if(owner!==guideOwner)return;
        conversation.value = created.id;
        messages.value = []; guideProposal.value = null; question.value = "";
      });
    }
    function guideResultText(value) {
      if (!value) return "";
      if (value.status === "PENDING") return "尚未提交，请核对场次和操作类型后明确确认。";
      if (value.status === "PROCESSING") return "请求已提交，正在核对结果。刷新状态不会重复报名；恢复时只会使用原请求。";
      if (value.status === "SUCCEEDED") {
        const status = value.participation?.status;
        return status === "RESERVED" ? "预约成功，已获得正式参与资格。" : status === "WAITING" ? "已加入候补队列，尚未获得正式名额。" : "该参与资格当前为“" + label(status) + "”，不会再次报名。";
      }
      if (value.status === "REJECTED") return "该方案已取消或被新需求替代，没有提交新的预约。";
      if (value.status === "EXPIRED") return "确认方案已过期，请重新查询场次。";
      return errorMessage(value.errorCode, 409);
    }
    watch(me, (value) => {
      const owner = value?.user?.id || value?.user?.username || "";
      if (owner === guideOwner) return;
      stopGuide();clearInterval(proposalPoll); guideOwner = owner;
      conversation.value = ""; messages.value = []; guideProposal.value = null;
      if (owner) try {
        const saved = JSON.parse(sessionStorage.getItem("guide:" + owner) || "null");
        if (saved) { conversation.value = saved.conversation || ""; messages.value = saved.messages || []; guideProposal.value = saved.proposal || null; }
      } catch { sessionStorage.removeItem("guide:" + owner); }
    }, {flush:"sync"});
    watch([messages, conversation, guideProposal], () => {
      if (guideOwner) sessionStorage.setItem("guide:" + guideOwner, JSON.stringify({conversation:conversation.value,messages:messages.value.slice(-20),proposal:guideProposal.value}));
    }, {deep:true});
    watch(page, value => { if (value !== "guide") {stopGuide();clearInterval(proposalPoll);} });
    async function plan() {
      await run(async () => {
        const r = await api("/admin/ai/activity-plans", "POST", {
          venue_id: Number(form.value.venue_id),
          theme: form.value.theme,
          minutes: Number(form.value.minutes),
          audience: form.value.audience,
          requirements: form.value.requirements,
        });
        form.value = {
          venue_id: Number(form.value.venue_id),
          ...r,
          plan: r.steps.map((step, index) =>
            (index + 1) + ". " + step.name + "（" + step.minutes + "分钟）\n" + step.description).join("\n\n"),
          category: "CULTURE",
          audience: form.value.audience,
        };
        setView("editActivity");
      });
    }
    async function report() {
      await run(
        async () =>
          (result.value = await api(
            "/admin/ai/reports",
            "POST",
            form.value.report_scope === "activity"
              ? { activity_id: Number(form.value.scope_id) }
              : { session_id: Number(form.value.scope_id) },
          )),
      );
    }
    async function stats() {
      await run(
        async () =>
          (result.value = await api(
            "/admin/statistics?venueId=" +
              form.value.venue_id +
              "&from=" +
              form.value.from +
              "&to=" +
              form.value.to,
          )),
      );
    }
    async function venueSave() {
      await run(async () => {
        await api("/admin/venues", "POST", form.value);
        await nav("venues");
        toast.value = "场馆资料已保存";
      });
    }
    async function selectGrantUser() {
      toast.value = "";
      const request = ++grantEpoch, epoch = viewEpoch, user = grantUserId.value;
      grantReady.value = false;
      grantVenueIds.value = [];
      grantOriginalIds.value = [];
      if (!user) return;
      await run(async () => {
        try {
          const current = await api("/admin/users/" + user + "/venue-grants");
          if (request !== grantEpoch || epoch !== viewEpoch) return;
          grantVenueIds.value = current.venue_ids.map(Number);
          grantOriginalIds.value = [...grantVenueIds.value];
          grantReady.value = true;
        } catch (e) {
          if (request === grantEpoch && epoch === viewEpoch) throw e;
        }
      });
    }
    function toggleGrantVenue(id, checked) {
      if (!grantReady.value || busy.value) return;
      grantVenueIds.value = checked ? [...new Set([...grantVenueIds.value, id])] :
        grantVenueIds.value.filter(v => v !== id);
    }
    async function grant() {
      const user = grantUserId.value, ids = [...grantVenueIds.value], epoch = viewEpoch, request = grantEpoch;
      if (!grantReady.value || !user || !grantChanged.value || busy.value) return;
      if (!ids.length && grantOriginalIds.value.length &&
          !window.confirm("确认清空该用户的全部场馆授权？该用户将无法管理任何场馆。")) return;
      await run(async () => {
        await api("/admin/users/" + user + "/venue-grants", "PUT", ids);
        if (request !== grantEpoch || epoch !== viewEpoch) return;
        grantOriginalIds.value = [...ids];
        toast.value = ids.length ? "已保存 " + grantUser.value.username + " 的场馆授权，共 " + ids.length + " 个场馆" : "已清空该用户的场馆授权";
      });
    }
    async function read(row) {
      await run(async () => {
        await api("/me/notifications/" + row.id + "/read", "PATCH", {});
        row.read_at = new Date().toISOString();
      });
    }
    watch(result, (value) => {
      if (value)
        nextTick(() => {
          document
            .querySelector("#operation-result")
            ?.scrollIntoView({ behavior: "smooth", block: "start" });
        });
    });
    watch(error, (value) => {
      if (value)
        nextTick(() =>
          document
            .querySelector('[role="alert"]')
            ?.scrollIntoView({ behavior: "smooth", block: "center" }),
        );
    });
    const tick = setInterval(() => (clock.value = Date.now()), 1000);
    onUnmounted(() => {
      stopGuide();
      clearInterval(tick);
      clearInterval(proposalPoll);
      window.removeEventListener("popstate", openRoute);
    });
    onMounted(async () => {
      window.addEventListener("popstate", openRoute);
      if (token.value)
        await run(async () => {
          try {
            me.value = await api("/auth/me");
          } catch {
            sessionStorage.removeItem("token");
            token.value = "";
            toast.value = "登录已过期，请重新登录";
          }
        });
      await openRoute();
    });
    return {
      pageMeta,
      section,
      platform,
      back,
      categories,
      managementStatus,
      venueCategories,
      venueFilter,
      venueCategory,
      venueQuery,
      publicVenues,
      visibleVenues,
      pageCount,
      sessionTimeKeys,
      sessionFields,
      updateSessionTime,
      updateCapacity,
      grantUserId,
      grantVenueIds,
      grantOriginalIds,
      grantReady,
      grantUserQuery,
      grantVenueQuery,
      grantUser,
      grantUsers,
      grantVenues,
      grantChanged,
      selectGrantUser,
      toggleGrantVenue,
      searchVenueActivities: (id) => {
        venueFilter.value = id;
        q.value = "";
        category.value = "";
        pages.activities = 1;
        return nav("activities");
      },
      audiences,
      date,
      clock,
      ticket,
      copyCredential,
      resultTitle,
      canReview: (r) =>
        r.status === "CHECKED_IN" && stamp(r.end_at) <= clock.value,
      canCancel: (r) =>
        ["RESERVED", "OFFERED", "WAITING"].includes(r.status) &&
        stamp(r.start_at) > clock.value &&
        (r.status !== "OFFERED" || stamp(r.confirm_deadline) > clock.value),
      countdown: (r) => {
        const seconds = Math.max(
          0,
          Math.floor((stamp(r.confirm_deadline) - clock.value) / 1000),
        );
        return seconds
          ? Math.floor(seconds / 60) + "分" + (seconds % 60) + "秒"
          : "确认已截止，等待状态更新";
      },
      canBook: (x) =>
        x.status === "PUBLISHED" &&
        stamp(x.registration_start_at) <= clock.value &&
        stamp(x.registration_end_at) > clock.value &&
        stamp(x.start_at) > clock.value,
      go: (p) => {
        if (p !== page.value && ["activities", "manage"].includes(p)) venueFilter.value = 0;
        pages[p] = 1;
        return nav(p);
      },
      search: () => {
        pages[page.value] = 1;
        return nav(page.value);
      },
      paginate: (delta) => {
        pages[page.value] = Math.max(1, pageNo.value + delta);
        return nav(page.value);
      },
      token,
      me,
      page,
      rows,
      sessions,
      selected,
      error,
      busy,
      result,
      qr,
      q,
      category,
      pageNo,
      auth,
      form,
      venues,
      admin,
      messages,
      conversation,
      guideProposal,
      guideServerOffset,
      guideExpired: computed(() => guideProposal.value && stamp(guideProposal.value.expiresAt) <= clock.value + guideServerOffset.value),
      selectGuideCandidate,
      confirmGuideProposal,
      rejectGuideProposal,
      refreshGuideProposal,
      newGuideConversation,
      guideResultText,
      prepareFailedWaitlist: () => {
        const candidate=messages.value.filter(m=>m.current).flatMap(m=>m.cards||[]).find(c=>c.sessionId===guideProposal.value?.session.sessionId);
        if(candidate)return selectGuideCandidate(candidate,'WAITLIST');
        error.value='推荐已失效，请先重新查询场次，再单独选择候补';
      },
      question,
      api,
      run,
      nav,
      login,
      logout,
      detail,
      book,
      act,
      credential,
      edit,
      newActivity,
      saveActivity,
      newSession,
      saveSession,
      manageAction,
      sessionAction,
      review,
      saveReview,
      check,
      chat,
      stopGuide,guideStreaming,guideProgress,
      plan,
      report,
      stats,
      venueSave,
      grant,
      read,
      JSON,
      total,
      toast,
      venueInfo,
      cover: (category) =>
        "/covers/" +
        ([
          "HERITAGE",
          "LECTURE",
          "ART",
          "CULTURE",
          "FAMILY",
          "TRADITION",
        ].includes(category)
          ? category
          : "CULTURE") +
        ".svg",
      showVenue,
      sessionLabel: (x) =>
        x.status === "CANCELLED"
          ? "已取消"
          : x.status === "DRAFT"
            ? "草稿"
            : Date.parse(x.end_at) < Date.now()
              ? "已结束"
              : Date.parse(x.registration_end_at) < Date.now()
                ? "报名已截止"
                : Date.parse(x.registration_start_at) > Date.now()
                  ? "尚未开放报名"
                  : x.occupied_count >= x.capacity
                    ? "名额已满，可候补"
                    : "可预约",
      label,
      displayResult,
      notificationText,
    };
  },
  template,
};
if (typeof document !== "undefined" && document.querySelector("#app"))
  createApp(App).mount("#app");
