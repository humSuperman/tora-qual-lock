# 资格日志查询页面

后端先开启接口：

```yaml
tora:
  qual:
    web:
      enabled: true
      path: /tora-qual      # 默认值
      token:                # 可选，配了页面也要填
```

所有页面功能一样：上方「当前资格」（总资格 / 已用 / 可用 / 有效期规则 / 到期时间，已用超过总资格时提示「已超用」），下方按资格名、后缀、时间范围、类型、更新内容、业务单号筛选日志并分页。
项目接口有统一返回体（如 `{code, data}`）时，页面会自动取 `data`。

| 页面 | 依赖 | 怎么接入 | 改哪里 |
|---|---|---|---|
| `html/tora-qual-log.html` | 无 | 放到 `src/main/resources/static/`，访问 `/tora-qual-log.html` | 文件里的 `TORA_QUAL = { base, token, headers }` |
| `h5/tora-qual-log-h5.html` | 无 | 同上，手机访问 | 同上 |
| `vue2/ToraQualLog.vue` | Vue 2 + Element UI 2.15+ | 复制到 `src/views/`，路由里注册 | props：`base`、`token`、`request` |
| `vue3/ToraQualLog.vue` | Vue 3 + Element Plus | 同上 | 同上 |
| `react/ToraQualLog.jsx` | React 16.8+ + antd 4 / 5 | 复制到 `src/pages/`，路由里注册 | 同上 |
| `uniapp/tora-qual-log.vue` | uni-app（Vue 2 / 3 都行） | 复制到 `pages/`，在 `pages.json` 注册 | 文件顶部 `BASE`、`TOKEN`、`headers` |

## 带上项目自己的登录凭证

**纯 HTML**：改 `headers`。

```js
var TORA_QUAL = {
  base: '/tora-qual',
  token: '',
  headers: function () { return { Authorization: 'Bearer ' + localStorage.getItem('token') }; }
};
```

**Vue / React**：推荐直接传项目封装好的请求函数，拦截器、登录态、报错提示都沿用项目的。`request(url, params, headers)` 返回 Promise，结果是接口返回的 JSON（或项目统一返回体）；`url` = `base` + `/logs` 或 `/quota`，`headers` 里是 `X-Qual-Token`（配了 token 时）。

```vue
<!-- Vue：axios 实例 -->
<ToraQualLog base="/api/tora-qual" :request="(url, params, headers) => http.get(url, { params, headers }).then(r => r.data)" />

<!-- 若依 -->
<tora-qual-log base="/dev-api/tora-qual" :request="(url, params, headers) => request({ url, method: 'get', params, headers })" />
```

```jsx
// React
<ToraQualLog base="/api/tora-qual" request={(url, params, headers) => axios.get(url, { params, headers }).then(r => r.data)} />
```

不传 `request` 时用 `fetch` 请求 `base + path`，带 `token` 和 cookie。

**uni-app**：小程序 / App 的 `BASE` 要写完整域名，并把域名加到小程序后台的 request 合法域名里。支持页面参数 `?name=xxx&suffix=yyy` 直接打开某个资格。
