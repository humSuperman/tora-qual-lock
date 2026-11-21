<template>
  <!-- 资格日志查询（Vue 2 + Element UI）。用法：<tora-qual-log base="/dev-api/tora-qual" :request="myRequest" /> -->
  <div class="tora-qual">
    <el-card shadow="never" class="block">
      <div slot="header">当前资格</div>
      <el-form :inline="true" size="small" @submit.native.prevent>
        <el-form-item label="资格名"><el-input v-model="q.name" placeholder="如 lottery:prize" clearable /></el-form-item>
        <el-form-item label="后缀"><el-input v-model="q.suffix" placeholder="留空为共用资格" clearable /></el-form-item>
        <el-form-item><el-button type="primary" :loading="q.loading" @click="loadQuota">查看</el-button></el-form-item>
      </el-form>
      <el-descriptions v-if="q.data && q.data.exists" :column="5" border size="small">
        <el-descriptions-item label="总资格">{{ q.data.total }}</el-descriptions-item>
        <el-descriptions-item label="已用">{{ q.data.used }}</el-descriptions-item>
        <el-descriptions-item label="可用">
          <span :class="{ neg: q.data.available < 0 }">{{ q.data.available }}</span>
          <el-tag v-if="q.data.overused" type="danger" size="mini">已超用</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="有效期">{{ q.data.rule }}</el-descriptions-item>
        <el-descriptions-item label="到期时间">{{ q.data.expireAt || '永不过期' }}</el-descriptions-item>
      </el-descriptions>
      <div v-else-if="q.data" class="muted">Redis 里没有这个资格（还没用过，或已过期 / 删除）</div>
    </el-card>

    <el-card shadow="never" class="block">
      <div slot="header">变更日志</div>
      <el-form :inline="true" size="small" @submit.native.prevent>
        <el-form-item label="资格名"><el-input v-model="form.name" placeholder="如 lottery:prize" clearable /></el-form-item>
        <el-form-item label="后缀"><el-input v-model="form.suffix" :disabled="form.shared" placeholder="用户 id" clearable /></el-form-item>
        <el-form-item><el-checkbox v-model="form.shared">只看共用资格</el-checkbox></el-form-item>
        <el-form-item label="时间">
          <el-date-picker v-model="form.range" type="datetimerange" value-format="yyyy-MM-dd HH:mm:ss"
                          start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.types" multiple clearable placeholder="全部" style="width: 180px">
            <el-option v-for="(v, k) in TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="更新内容">
          <el-select v-model="form.fields" multiple clearable placeholder="全部" style="width: 200px">
            <el-option v-for="(v, k) in FIELD" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务单号"><el-input v-model="form.bizId" clearable /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="list" size="small" border>
        <el-table-column prop="createdAt" label="时间" width="160" />
        <el-table-column label="资格" min-width="160">
          <template slot-scope="{ row }">{{ row.name }}<span v-if="row.suffix">:{{ row.suffix }}</span><span v-else class="muted">（共用）</span></template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template slot-scope="{ row }">
            <el-tag :type="tagType(row.type)" size="mini">{{ TYPE[row.type] }}<span v-if="row.field">·{{ FIELD[row.field] }}</span></el-tag>
          </template>
        </el-table-column>
        <el-table-column label="变化" width="80" align="right">
          <template slot-scope="{ row }"><span :class="row.amount < 0 ? 'neg' : row.amount > 0 ? 'pos' : ''">{{ row.amount > 0 ? '+' : '' }}{{ row.amount }}</span></template>
        </el-table-column>
        <el-table-column label="总资格" width="100" align="right">
          <template slot-scope="{ row }">{{ arrow(row.totalBefore, row.totalAfter) }}</template>
        </el-table-column>
        <el-table-column label="已用" width="100" align="right">
          <template slot-scope="{ row }">{{ arrow(row.usedBefore, row.usedAfter) }}</template>
        </el-table-column>
        <el-table-column label="可用" width="100" align="right">
          <template slot-scope="{ row }">{{ arrow(row.availableBefore, row.availableAfter) }}</template>
        </el-table-column>
        <el-table-column label="到期时间" min-width="170">
          <template slot-scope="{ row }">{{ expire(row) }}</template>
        </el-table-column>
        <el-table-column prop="bizId" label="业务单号" min-width="120" />
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next" :total="total"
                     :current-page="pageNum" :page-size="pageSize" :page-sizes="[20, 50, 100]"
                     @current-change="search" @size-change="sizeChange" />
    </el-card>
  </div>
</template>

<script>
const TYPE = { CREATE: '创建', UPDATE: '更新', DELETE: '删除' }
const FIELD = { TOTAL: '总资格', AVAILABLE: '可用资格', EXPIRE: '有效期' }

export default {
  name: 'ToraQualLog',
  props: {
    /** 接口前缀：tora.qual.web.path，前面加上 context-path / 网关前缀（若依是 /dev-api） */
    base: { type: String, default: '/tora-qual' },
    /** 配了 tora.qual.web.token 时传 */
    token: { type: String, default: '' },
    /**
     * 可选：用项目自己的请求方法（带登录凭证、统一报错），签名 (url, params, headers) => Promise<响应体>。
     * 若依可以传：(url, params, headers) => request({ url, method: 'get', params, headers })
     */
    request: { type: Function, default: null }
  },
  data() {
    return {
      TYPE, FIELD,
      form: { name: '', suffix: '', shared: false, range: null, types: [], fields: [], bizId: '' },
      list: [], total: 0, pageNum: 1, pageSize: 20, loading: false,
      q: { name: '', suffix: '', loading: false, data: null }
    }
  },
  mounted() {
    this.search(1)
  },
  methods: {
    async call(path, params) {
      const clean = {}
      Object.keys(params).forEach(k => {
        if (params[k] !== '' && params[k] != null) clean[k] = params[k]
      })
      const headers = this.token ? { 'X-Qual-Token': this.token } : {}
      let res
      if (this.request) {
        res = await this.request(this.base + path, clean, headers)
      } else {
        const qs = Object.keys(clean).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(clean[k])).join('&')
        const r = await fetch(this.base + path + (qs ? '?' + qs : ''), { headers, credentials: 'include' })
        res = await r.json().catch(() => ({}))
        if (!r.ok) throw new Error(res.error || res.msg || 'HTTP ' + r.status)
      }
      // 项目有统一返回体（{ code, data }）时取 data
      if (res && res.list === undefined && res.exists === undefined && res.data !== undefined) res = res.data
      return res
    },
    async search(page) {
      this.pageNum = page || this.pageNum
      this.loading = true
      const f = this.form
      try {
        const res = await this.call('/logs', {
          name: f.name.trim(), suffix: f.shared ? '' : f.suffix.trim(), shared: f.shared ? 1 : '',
          from: f.range ? f.range[0] : '', to: f.range ? f.range[1] : '',
          types: f.types.join(','), fields: f.fields.join(','), bizId: f.bizId.trim(),
          pageNum: this.pageNum, pageSize: this.pageSize
        })
        this.list = res.list || []
        this.total = res.total || 0
      } catch (e) {
        this.$message.error('查询失败：' + e.message)
      } finally {
        this.loading = false
      }
    },
    sizeChange(size) {
      this.pageSize = size
      this.search(1)
    },
    reset() {
      this.form = { name: '', suffix: '', shared: false, range: null, types: [], fields: [], bizId: '' }
      this.search(1)
    },
    async loadQuota() {
      if (!this.q.name.trim()) return this.$message.warning('请输入资格名')
      this.q.loading = true
      try {
        this.q.data = await this.call('/quota', { name: this.q.name.trim(), suffix: this.q.suffix.trim() })
      } catch (e) {
        this.$message.error('查询失败：' + e.message)
      } finally {
        this.q.loading = false
      }
    },
    arrow(a, b) {
      return a === b ? a : a + ' → ' + b
    },
    expire(row) {
      return row.expireBefore === row.expireAfter ? (row.expireAfter || '永不过期')
        : (row.expireBefore || '-') + ' → ' + (row.expireAfter || '-')
    },
    tagType(t) {
      return t === 'CREATE' ? 'success' : t === 'DELETE' ? 'danger' : ''
    }
  }
}
</script>

<style scoped>
.tora-qual .block { margin-bottom: 12px; }
.tora-qual .pager { margin-top: 12px; text-align: right; }
.tora-qual .muted { color: #909399; }
.tora-qual .neg { color: #f56c6c; }
.tora-qual .pos { color: #67c23a; }
</style>
