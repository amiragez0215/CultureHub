import { h } from "vue";
import { label, notificationText } from "./locale.js";
const hidden = new Set([
  "active_guard",
  "request_id",
  "password_hash",
  "source_event_id",
  "eventVersion",
]);
function valueText(value, key) {
  if (value == null) return key === "checkinRate" ? "场次结束后生成" : "暂无";
  if (typeof value === "boolean") return value ? "是" : "否";
  if (
    ["checkinRate", "checkinProgress"].includes(key) &&
    typeof value === "number"
  )
    return (value * 100).toFixed(1) + "%";
  if (key === "body") return notificationText(value);
  return label(value);
}
const DataView = {
  name: "DataView",
  props: ["value", "depth"],
  setup(props) {
    return () => {
      const value = props.value,
        depth = props.depth || 0;
      if (value == null) return h("p", { class: "muted" }, "暂无记录");
      if (Array.isArray(value)) {
        if (!value.length) return h("p", { class: "empty" }, "暂无记录");
        if (
          value.every((v) => v && typeof v === "object" && !Array.isArray(v))
        ) {
          const keys = [
            ...new Set(value.flatMap((v) => Object.keys(v))),
          ].filter((k) => !hidden.has(k));
          return h("div", { class: "table-scroll" }, [
            h("table", [
              h("thead", [
                h(
                  "tr",
                  keys.map((k) => h("th", label(k))),
                ),
              ]),
              h(
                "tbody",
                value.map((row, index) =>
                  h(
                    "tr",
                    { key: row.id || index },
                    keys.map((k) =>
                      h(
                        "td",
                        typeof row[k] === "object" && row[k] != null
                          ? h(DataView, { value: row[k], depth: depth + 1 })
                          : String(valueText(row[k], k)),
                      ),
                    ),
                  ),
                ),
              ),
            ]),
          ]);
        }
        return h(
          "ul",
          { class: "result-list" },
          value.map((v, i) =>
            h("li", { key: i }, [
              typeof v === "object"
                ? h(DataView, { value: v, depth: depth + 1 })
                : String(valueText(v)),
            ]),
          ),
        );
      }
      if (typeof value === "object")
        return h(
          "div",
          { class: "data-fields" },
          Object.entries(value)
            .filter(([k]) => !hidden.has(k))
            .map(([key, v]) =>
              h("div", { class: "data-field" }, [
                h("strong", label(key)),
                typeof v === "object" && v != null
                  ? h(DataView, { value: v, depth: depth + 1 })
                  : h("span", String(valueText(v, key))),
              ]),
            ),
        );
      return h("span", String(valueText(value)));
    };
  },
};
export default DataView;
