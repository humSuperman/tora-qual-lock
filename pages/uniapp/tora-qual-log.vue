<template>
  <!-- 资格日志查询（uni-app，H5 / 小程序 / App 通用，Vue 2、3 都能用）。放到 pages/ 下并在 pages.json 注册 -->
  <view class="page">
    <view class="search">
      <input class="kw" v-model="form.name" placeholder="资格名，如 lottery:prize" confirm-type="search" @confirm="go" />
      <button class="btn" size="mini" @click="showFilter = !showFilter">筛选</button>
    </view>

    <view v-if="showFilter" class="filter">
      <view class="field"><text>后缀</text><input v-model="form.suffix" :disabled="form.shared" placeholder="用户 id" /></view>
      <view class="field">
        <text>只看共用资格</text>
        <switch :checked="form.shared" @change="form.shared = $event.detail.value" />
      </view>
      <view class="field">
        <text>开始日期</text>
        <picker mode="date" :value="form.from" @change="form.from = $event.detail.value"><view class="pick">{{ form.from || '不限' }}</view></picker>
      </view>
      <view class="field">
        <text>结束日期</text>
        <picker mode="date" :value="form.to" @change="form.to = $event.detail.value"><view class="pick">{{ form.to || '不限' }}</view></picker>
      </view>
      <view class="field">
        <text>类型</text>
        <picker :range="typeOptions" range-key="label" @change="form.type = typeOptions[$event.detail.value].value">
          <view class="pick">{{ label(typeOptions, form.type) }}</view>
        </picker>
      </view>
      <view class="field">
        <text>更新内容</text>
        <picker :range="fieldOptions" range-key="label" @change="form.field = fieldOptions[$event.detail.value].value">
          <view class="pick">{{ label(fieldOptions, form.field) }}</view>
        </picker>
      </view>
      <view class="field"><text>业务单号</text><input v-model="form.bizId" /></view>
      <view class="actions">
        <button size="mini" @click="reset">重置</button>
        <button size="mini" type="primary" @click="go">查询</button>
      </view>
    </view>

    <view v-if="quota" class="quota">
      <view v-if="!quota.exists" class="muted">Redis 里没有这个资格（还没用过，或已过期 / 删除）</view>
      <block v-else>
        <view class="grid">
          <view><text class="big">{{ quota.total }}</text><text>总资格</text></view>
          <view><text class="big">{{ quota.used }}</text><text>已用</text></view>
          <view><text :class="['big', quota.available < 0 ? 'neg' : '']">{{ quota.available }}</text><text>可用</text></view>
        </view>
        <view class="meta">{{ quota.rule }} · 到期 {{ quota.expireAt || '永不过期' }}<text v-if="quota.overused" class="neg"> · 已超用</text></view>
      </block>
    </view>

    <view v-for="(l, i) in list" :key="l.id || i" class="item">
      <view class="row">
        <text class="name">{{ l.name }}{{ l.suffix ? ':' + l.suffix : '（共用）' }}</text>
        <text :class="['tag', l.type]">{{ TYPE[l.type] }}{{ l.field ? '·' + FIELD[l.field] : '' }}</text>
      </view>
      <view class="row">
        <text class="muted">{{ l.createdAt }}</text>
        <text :class="['amount', l.amount < 0 ? 'neg' : l.amount > 0 ? 'pos' : '']">{{ l.amount > 0 ? '+' : '' }}{{ l.amount }}</text>
      </view>
      <view class="kv">
        <text>总资格 {{ arrow(l.totalBefore, l.totalAfter) }}　已用 {{ arrow(l.usedBefore, l.usedAfter) }}　可用 {{ arrow(l.availableBefore, l.availableAfter) }}</text>
        <text>到期 {{ expire(l) }}</text>
        <text v-if="l.bizId">单号 {{ l.bizId }}</text>
        <text v-if="l.remark">备注 {{ l.remark }}</text>
      </view>
    </view>
    <view :class="error ? 'error' : 'muted'" class="foot">{{ footText }}</view>
  </view>
</template>

<script>
/* ======== 按项目改这里 ======== */
// 接口地址：H5 可以写相对路径；小程序 / App 要写完整域名，如 https://api.example.com/tora-qual
const BASE = '/tora-qual'
// 配了 tora.qual.web.token 时填上
const TOKEN = ''
// 项目自己的登录凭证
const headers = () => ({ /* Authorization: 'Bearer ' + uni.getStorageSync('token') */ })
/* ============================== */

const TYPE = { CREATE: '创建', UPDATE: '更新', DELETE: '删除' }
const FIELD = { TOTAL: '总资格', AVAILABLE: '可用资格', EXPIRE: '有效期' }
const PAGE_SIZE = 20
const emptyForm = () => ({ name: '', suffix: '', shared: false, from: '', to: '', type: '', field: '', bizId: '' })

function call(path, params) {
  const data = {}
  Object.keys(params).forEach(k => {
    if (params[k] !== '' && params[k] != null) data[k] = params[k]
  })
  const h = headers()
  if (TOKEN) h['X-Qual-Token'] = TOKEN
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE + path, data, header: h,
      success: r => {
        let body = r.data || {}
        if (r.statusCode >= 400) return reject(new Error(body.error || body.msg || 'HTTP ' + r.statusCode))
        // 项目有统一返回体（{ code, data }）时取 data
        if (body.list === undefined && body.exists === undefined && body.data !== undefined) body = body.data
        resolve(body)
      },
      fail: e => reject(new Error(e.errMsg || '网络错误'))
    })
  })
}

export default {
  data() {
    return {
      TYPE, FIELD,
      typeOptions: [{ value: '', label: '全部' }].concat(Object.keys(TYPE).map(k => ({ value: k, label: TYPE[k] }))),
      fieldOptions: [{ value: '', label: '全部' }].concat(Object.keys(FIELD).map(k => ({ value: k, label: FIELD[k] }))),
      form: emptyForm(),
      showFilter: false,
      list: [], total: 0, pageNum: 1, loading: false, done: false, error: '',
      quota: null
    }
  },
  computed: {
    footText() {
      if (this.error) return '查询失败：' + this.error
      if (this.loading) return '加载中…'
      if (!this.total) return '没有数据'
      return this.done ? '共 ' + this.total + ' 条，没有更多了' : '上拉加载更多'
    }
  },
  onLoad(options) {
    if (options && options.name) this.form.name = decodeURIComponent(options.name)
    if (options && options.suffix) this.form.suffix = decodeURIComponent(options.suffix)
    this.go()
  },
  onReachBottom() {
    this.load(false)
  },
  onPullDownRefresh() {
    this.go().then(() => uni.stopPullDownRefresh())
  },
  methods: {
    go() {
      this.showFilter = false
      this.loadQuota()
      return this.load(true)
    },
    reset() {
      this.form = emptyForm()
    },
    load(reset) {
      if (this.loading || (!reset && this.done)) return Promise.resolve()
      if (reset) {
        this.pageNum = 1
        this.done = false
        this.list = []
      }
      this.loading = true
      this.error = ''
      const f = this.form
      return call('/logs', {
        name: f.name.trim(), suffix: f.shared ? '' : f.suffix.trim(), shared: f.shared ? 1 : '',
        from: f.from ? f.from + ' 00:00:00' : '', to: f.to ? f.to + ' 23:59:59' : '',
        types: f.type, fields: f.field, bizId: f.bizId.trim(), pageNum: this.pageNum, pageSize: PAGE_SIZE
      }).then(page => {
        this.list = this.list.concat(page.list || [])
        this.total = page.total || 0
        this.done = this.pageNum * PAGE_SIZE >= this.total
        this.pageNum++
      }).catch(e => {
        this.error = e.message
      }).then(() => {
        this.loading = false
      })
    },
    loadQuota() {
      const name = this.form.name.trim()
      if (!name) {
        this.quota = null
        return
      }
      call('/quota', { name, suffix: this.form.shared ? '' : this.form.suffix.trim() })
        .then(q => { this.quota = q })
        .catch(() => { this.quota = null })
    },
    label(options, value) {
      const o = options.filter(x => x.value === value)[0]
      return o ? o.label : '全部'
    },
    arrow(a, b) {
      return a === b ? a : a + ' → ' + b
    },
    expire(l) {
      return l.expireBefore === l.expireAfter ? (l.expireAfter || '永不过期') : (l.expireBefore || '-') + ' → ' + (l.expireAfter || '-')
    }
  }
}
</script>

<style>
.page { min-height: 100vh; background: #f5f6f7; padding-bottom: 20rpx; }
.search { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; gap: 16rpx; padding: 20rpx 24rpx; background: #fff; }
.kw { flex: 1; height: 68rpx; padding: 0 24rpx; border-radius: 34rpx; background: #f2f3f5; font-size: 28rpx; }
.btn { margin: 0; }
.filter { background: #fff; padding: 0 24rpx 24rpx; }
.field { display: flex; justify-content: space-between; align-items: center; min-height: 88rpx; border-bottom: 1rpx solid #eef0f1; font-size: 28rpx; color: #646a73; }
.field input { flex: 1; margin-left: 24rpx; text-align: right; }
.pick { color: #1f2329; }
.actions { display: flex; justify-content: flex-end; gap: 16rpx; margin-top: 24rpx; }
.quota, .item { margin: 20rpx 24rpx; padding: 24rpx; border-radius: 16rpx; background: #fff; }
.grid { display: flex; text-align: center; }
.grid > view { flex: 1; display: flex; flex-direction: column; font-size: 24rpx; color: #8f959e; }
.big { font-size: 40rpx; font-weight: 600; color: #1f2329; }
.meta { margin-top: 12rpx; text-align: center; font-size: 24rpx; color: #8f959e; }
.row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8rpx; }
.name { font-weight: 500; font-size: 28rpx; word-break: break-all; }
.tag { padding: 0 12rpx; border-radius: 6rpx; font-size: 22rpx; background: #eef3ff; color: #3370ff; }
.tag.CREATE { background: #e8f7ea; color: #2ea121; }
.tag.DELETE { background: #fdecec; color: #d83931; }
.amount { font-size: 34rpx; font-weight: 600; }
.kv { display: flex; flex-direction: column; font-size: 24rpx; color: #646a73; }
.muted { color: #8f959e; font-size: 24rpx; }
.neg { color: #d83931; }
.pos { color: #2ea121; }
.foot { text-align: center; padding: 24rpx; }
.error { color: #d83931; }
</style>
