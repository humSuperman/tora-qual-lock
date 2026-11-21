<template>
  <!-- 资格日志查询（Vue 3 + Element Plus）。用法：<ToraQualLog base="/api/tora-qual" :request="myRequest" /> -->
  <div class="tora-qual">
    <el-card shadow="never" class="block">
      <template #header>当前资格</template>
      <el-form inline @submit.prevent>
        <el-form-item label="资格名"><el-input v-model="q.name" placeholder="如 lottery:prize" clearable /></el-form-item>
        <el-form-item label="后缀"><el-input v-model="q.suffix" placeholder="留空为共用资格" clearable /></el-form-item>
        <el-form-item><el-button type="primary" :loading="q.loading" @click="loadQuota">查看</el-button></el-form-item>
      </el-form>
      <el-descriptions v-if="q.data && q.data.exists" :column="5" border>
        <el-descriptions-item label="总资格">{{ q.data.total }}</el-descriptions-item>
        <el-descriptions-item label="已用">{{ q.data.used }}</el-descriptions-item>
        <el-descriptions-item label="可用">
          <span :class="{ neg: q.data.available < 0 }">{{ q.data.available }}</span>
          <el-tag v-if="q.data.overused" type="danger" size="small">已超用</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="有效期">{{ q.data.rule }}</el-descriptions-item>
        <el-descriptions-item label="到期时间">{{ q.data.expireAt || '永不过期' }}</el-descriptions-item>
      </el-descriptions>
      <div v-else-if="q.data" class="muted">Redis 里没有这个资格（还没用过，或已过期 / 删除）</div>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>变更日志</template>
      <el-form inline @submit.prevent>
        <el-form-item label="资格名"><el-input v-model="form.name" placeholder="如 lottery:prize" clearable /></el-form-item>
        <el-form-item label="后缀"><el-input v-model="form.suffix" :disabled="form.shared" placeholder="用户 id" clearable /></el-form-item>
        <el-form-item><el-checkbox v-model="form.shared">只看共用资格</el-checkbox></el-form-item>
        <el-form-item label="时间">
          <el-date-picker v-model="form.range" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
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

      <el-table v-loading="loading" :data="list" border>
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="资格" min-width="160">
          <template #default="{ row }">{{ row.name }}<span v-if="row.suffix">:{{ row.suffix }}</span><span v-else class="muted">（共用）</span></template>
        </el-table-column>
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <el-tag :type="tagType(row.type)" size="small">{{ TYPE[row.type] }}<span v-if="row.field">·{{ FIELD[row.field] }}</span></el-tag>
          </template>
        </el-table-column>
        <el-table-column label="变化" width="80" align="right">
          <template #default="{ row }"><span :class="row.amount < 0 ? 'neg' : row.amount > 0 ? 'pos' : ''">{{ row.amount > 0 ? '+' : '' }}{{ row.amount }}</span></template>
        </el-table-column>
        <el-table-column label="总资格" width="100" align="right">
          <template #default="{ row }">{{ arrow(row.totalBefore, row.totalAfter) }}</template>
        </el-table-column>
        <el-table-column label="已用" width="100" align="right">
          <template #default="{ row }">{{ arrow(row.usedBefore, row.usedAfter) }}</template>
        </el-table-column>
        <el-table-column label="可用" width="100" align="right">
          <template #default="{ row }">{{ arrow(row.availableBefore, row.availableAfter) }}</template>
        </el-table-column>
        <el-table-column label="到期时间" min-width="180">
          <template #default="{ row }">{{ expire(row) }}</template>
        </el-table-column>
        <el-table-column prop="bizId" label="业务单号" min-width="120" />
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next" :total="total"
                     v-model:current-page="pageNum" v-model:page-size="pageSize" :page-sizes="[20, 50, 100]"
                     @current-change="search()" @size-change="search(1)" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  /** 接口前缀：tora.qual.web.path，前面加上 context-path / 网关前缀 */
  base: { type: String, default: '/tora-qual' },
  /** 配了 tora.qual.web.token 时传 */
  token: { type: String, default: '' },
  /** 可选：项目自己的请求方法 (url, params, headers) => Promise<响应体>，比如 axios 实例的 get */
  request: { type: Function, default: null }
})

const TYPE = { CREATE: '创建', UPDATE: '更新', DELETE: '删除' }
const FIELD = { TOTAL: '总资格', AVAILABLE: '可用资格', EXPIRE: '有效期' }
const emptyForm = () => ({ name: '', suffix: '', shared: false, range: null, types: [], fields: [], bizId: '' })

const form = reactive(emptyForm())
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const q = reactive({ name: '', suffix: '', loading: false, data: null })

async function call(path, params) {
  const clean = {}
  Object.keys(params).forEach(k => {
    if (params[k] !== '' && params[k] != null) clean[k] = params[k]
  })
  const headers = props.token ? { 'X-Qual-Token': props.token } : {}
  let res
  if (props.request) {
    res = await props.request(props.base + path, clean, headers)
  } else {
    const r = await fetch(props.base + path + '?' + new URLSearchParams(clean), { headers, credentials: 'include' })
    res = await r.json().catch(() => ({}))
    if (!r.ok) throw new Error(res.error || res.msg || 'HTTP ' + r.status)
  }
  // 项目有统一返回体（{ code, data }）时取 data
  if (res && res.list === undefined && res.exists === undefined && res.data !== undefined) res = res.data
  return res
}

async function search(page) {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const res = await call('/logs', {
      name: form.name.trim(), suffix: form.shared ? '' : form.suffix.trim(), shared: form.shared ? 1 : '',
      from: form.range ? form.range[0] : '', to: form.range ? form.range[1] : '',
      types: form.types.join(','), fields: form.fields.join(','), bizId: form.bizId.trim(),
      pageNum: pageNum.value, pageSize: pageSize.value
    })
    list.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error('查询失败：' + e.message)
  } finally {
    loading.value = false
  }
}

function reset() {
  Object.assign(form, emptyForm())
  search(1)
}

async function loadQuota() {
  if (!q.name.trim()) return ElMessage.warning('请输入资格名')
  q.loading = true
  try {
    q.data = await call('/quota', { name: q.name.trim(), suffix: q.suffix.trim() })
  } catch (e) {
    ElMessage.error('查询失败：' + e.message)
  } finally {
    q.loading = false
  }
}

const arrow = (a, b) => (a === b ? a : `${a} → ${b}`)
const expire = row => (row.expireBefore === row.expireAfter ? (row.expireAfter || '永不过期')
  : `${row.expireBefore || '-'} → ${row.expireAfter || '-'}`)
const tagType = t => (t === 'CREATE' ? 'success' : t === 'DELETE' ? 'danger' : 'primary')

onMounted(() => search(1))
</script>

<style scoped>
.tora-qual .block { margin-bottom: 12px; }
.tora-qual .pager { margin-top: 12px; justify-content: flex-end; }
.tora-qual .muted { color: var(--el-text-color-secondary, #909399); }
.tora-qual .neg { color: var(--el-color-danger, #f56c6c); }
.tora-qual .pos { color: var(--el-color-success, #67c23a); }
</style>
