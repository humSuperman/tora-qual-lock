/**
 * 资格日志查询（React 16.8+ / Ant Design 4、5 通用）。
 * 用法：<ToraQualLog base="/api/tora-qual" request={(url, params, headers) => axios.get(url, { params, headers }).then(r => r.data)} />
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Button, Card, Checkbox, DatePicker, Descriptions, Input, Select, Space, Table, Tag, message } from 'antd';

const TYPE = { CREATE: '创建', UPDATE: '更新', DELETE: '删除' };
const FIELD = { TOTAL: '总资格', AVAILABLE: '可用资格', EXPIRE: '有效期' };
const TAG_COLOR = { CREATE: 'green', UPDATE: 'blue', DELETE: 'red' };
const EMPTY = { name: '', suffix: '', shared: false, range: ['', ''], types: [], fields: [], bizId: '' };

const arrow = (a, b) => (a === b ? a : `${a} → ${b}`);
const expire = (r) => (r.expireBefore === r.expireAfter ? (r.expireAfter || '永不过期')
  : `${r.expireBefore || '-'} → ${r.expireAfter || '-'}`);

/**
 * @param base    接口前缀：tora.qual.web.path，前面加上 context-path / 网关前缀
 * @param token   配了 tora.qual.web.token 时传
 * @param request 可选：项目自己的请求方法 (url, params, headers) => Promise<响应体>
 */
export default function ToraQualLog({ base = '/tora-qual', token = '', request }) {
  const [form, setForm] = useState(EMPTY);
  const [query, setQuery] = useState(EMPTY);
  const [page, setPage] = useState({ pageNum: 1, pageSize: 20 });
  const [data, setData] = useState({ list: [], total: 0 });
  const [loading, setLoading] = useState(false);
  const [q, setQ] = useState({ name: '', suffix: '', loading: false, data: null });
  // 日期选择框不受控（避免依赖 moment / dayjs），重置时换个 key 让它清空
  const [pickerKey, setPickerKey] = useState(0);

  const call = useCallback(async (path, params) => {
    const clean = {};
    Object.keys(params).forEach((k) => {
      if (params[k] !== '' && params[k] != null) clean[k] = params[k];
    });
    const headers = token ? { 'X-Qual-Token': token } : {};
    let res;
    if (request) {
      res = await request(base + path, clean, headers);
    } else {
      const r = await fetch(`${base}${path}?${new URLSearchParams(clean)}`, { headers, credentials: 'include' });
      res = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(res.error || res.msg || `HTTP ${r.status}`);
    }
    // 项目有统一返回体（{ code, data }）时取 data
    if (res && res.list === undefined && res.exists === undefined && res.data !== undefined) res = res.data;
    return res;
  }, [base, token, request]);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    call('/logs', {
      name: query.name.trim(), suffix: query.shared ? '' : query.suffix.trim(), shared: query.shared ? 1 : '',
      from: query.range[0], to: query.range[1], types: query.types.join(','), fields: query.fields.join(','),
      bizId: query.bizId.trim(), pageNum: page.pageNum, pageSize: page.pageSize,
    }).then((res) => {
      if (alive) setData({ list: res.list || [], total: res.total || 0 });
    }).catch((e) => message.error(`查询失败：${e.message}`))
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [call, query, page]);

  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v && v.target ? (v.target.type === 'checkbox' ? v.target.checked : v.target.value) : v }));
  const search = () => { setQuery(form); setPage((p) => ({ ...p, pageNum: 1 })); };
  const reset = () => {
    setForm(EMPTY);
    setQuery(EMPTY);
    setPickerKey((k) => k + 1);
    setPage((p) => ({ ...p, pageNum: 1 }));
  };

  const loadQuota = async () => {
    if (!q.name.trim()) return message.warning('请输入资格名');
    setQ((s) => ({ ...s, loading: true }));
    try {
      const res = await call('/quota', { name: q.name.trim(), suffix: q.suffix.trim() });
      setQ((s) => ({ ...s, loading: false, data: res }));
    } catch (e) {
      message.error(`查询失败：${e.message}`);
      setQ((s) => ({ ...s, loading: false }));
    }
    return undefined;
  };

  const columns = [
    { title: '时间', dataIndex: 'createdAt', width: 170 },
    { title: '资格', render: (_, r) => (<>{r.name}{r.suffix ? `:${r.suffix}` : <span style={{ color: '#999' }}>（共用）</span>}</>) },
    { title: '类型', width: 140, render: (_, r) => <Tag color={TAG_COLOR[r.type]}>{TYPE[r.type]}{r.field ? `·${FIELD[r.field]}` : ''}</Tag> },
    {
      title: '变化', dataIndex: 'amount', width: 80, align: 'right',
      render: (v) => <span style={{ color: v < 0 ? '#cf1322' : v > 0 ? '#389e0d' : undefined }}>{v > 0 ? '+' : ''}{v}</span>,
    },
    { title: '总资格', width: 100, align: 'right', render: (_, r) => arrow(r.totalBefore, r.totalAfter) },
    { title: '已用', width: 100, align: 'right', render: (_, r) => arrow(r.usedBefore, r.usedAfter) },
    { title: '可用', width: 100, align: 'right', render: (_, r) => arrow(r.availableBefore, r.availableAfter) },
    { title: '到期时间', render: (_, r) => expire(r) },
    { title: '业务单号', dataIndex: 'bizId' },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
  ];
  const options = (m) => Object.keys(m).map((k) => ({ value: k, label: m[k] }));

  return (
    <div className="tora-qual">
      <Card title="当前资格" size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Input placeholder="资格名，如 lottery:prize" value={q.name} allowClear onChange={(e) => setQ({ ...q, name: e.target.value })} />
          <Input placeholder="后缀，留空为共用资格" value={q.suffix} allowClear onChange={(e) => setQ({ ...q, suffix: e.target.value })} />
          <Button type="primary" loading={q.loading} onClick={loadQuota}>查看</Button>
        </Space>
        {q.data && q.data.exists && (
          <Descriptions bordered size="small" column={5} style={{ marginTop: 12 }}>
            <Descriptions.Item label="总资格">{q.data.total}</Descriptions.Item>
            <Descriptions.Item label="已用">{q.data.used}</Descriptions.Item>
            <Descriptions.Item label="可用">
              <span style={{ color: q.data.available < 0 ? '#cf1322' : undefined }}>{q.data.available}</span>
              {q.data.overused && <Tag color="red" style={{ marginLeft: 6 }}>已超用</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="有效期">{q.data.rule}</Descriptions.Item>
            <Descriptions.Item label="到期时间">{q.data.expireAt || '永不过期'}</Descriptions.Item>
          </Descriptions>
        )}
        {q.data && !q.data.exists && <div style={{ marginTop: 12, color: '#999' }}>Redis 里没有这个资格（还没用过，或已过期 / 删除）</div>}
      </Card>

      <Card title="变更日志" size="small">
        <Space wrap style={{ marginBottom: 12 }}>
          <Input placeholder="资格名" value={form.name} allowClear onChange={set('name')} />
          <Input placeholder="后缀（用户 id）" value={form.suffix} disabled={form.shared} allowClear onChange={set('suffix')} />
          <Checkbox checked={form.shared} onChange={set('shared')}>只看共用资格</Checkbox>
          <DatePicker.RangePicker key={pickerKey} showTime onChange={(_, s) => set('range')(s && s[0] ? s : ['', ''])} />
          <Select mode="multiple" allowClear placeholder="类型" style={{ minWidth: 140 }} value={form.types} onChange={set('types')} options={options(TYPE)} />
          <Select mode="multiple" allowClear placeholder="更新内容" style={{ minWidth: 160 }} value={form.fields} onChange={set('fields')} options={options(FIELD)} />
          <Input placeholder="业务单号" value={form.bizId} allowClear onChange={set('bizId')} />
          <Button type="primary" onClick={search}>查询</Button>
          <Button onClick={reset}>重置</Button>
        </Space>
        <Table
          rowKey={(r) => r.id || `${r.createdAt}-${r.name}-${r.suffix}-${r.type}-${r.field}`}
          size="small"
          loading={loading}
          columns={columns}
          dataSource={data.list}
          scroll={{ x: 1200 }}
          pagination={{
            current: page.pageNum, pageSize: page.pageSize, total: data.total, showSizeChanger: true,
            pageSizeOptions: ['20', '50', '100'], showTotal: (t) => `共 ${t} 条`,
            onChange: (pageNum, pageSize) => setPage({ pageNum, pageSize }),
          }}
        />
      </Card>
    </div>
  );
}
