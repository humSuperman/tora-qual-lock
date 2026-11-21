-- 资格的原子操作。一个资格 = 一个 Hash：total 总资格、used 已用、rule 有效期规则；过期交给 key 的 TTL。
-- 每次请求都带上总资格和规则：和存的不一样就以请求的为准（已用不变），并返回变化让 Java 记日志。
-- KEYS[1] 资格 key
-- ARGV: 1 操作(peek/get/consume/restore/setAvailable/delete)  2 数量  3 总资格  4 规则文本
--       5 按规则从现在算的到期时间(毫秒，0=永不过期)  6 当前时间(毫秒)
-- 返回：{结果码, 是否新建, total, used, 剩余毫秒(-1 永不过期, -2 不存在), 原 total, 原 used,
--        总资格变了, 规则变了, 原剩余毫秒, 原规则}
-- 结果码：0 成功  1 可用不足  2 已过期  3 不存在  6 参数不对
local key = KEYS[1]
local op = ARGV[1]
local n = tonumber(ARGV[2])
local reqTotal = tonumber(ARGV[3])
local rule = ARGV[4]
local expAt = tonumber(ARGV[5])
local now = tonumber(ARGV[6])

local h = redis.call('HMGET', key, 'total', 'used', 'rule')
local exists = h[1] ~= false
local total = tonumber(h[1]) or 0
local used = tonumber(h[2]) or 0
local oldRule = h[3] or ''
local oldTotal, oldUsed = total, used
local oldPttl = -2
if exists then
  oldPttl = redis.call('PTTL', key)
end
local created, totalChanged, ruleChanged = 0, 0, 0

local function ret(code)
  local pttl = -2
  if redis.call('EXISTS', key) == 1 then
    pttl = redis.call('PTTL', key)
  elseif op == 'get' and code == 0 then
    -- 查询不存在的资格不创建，按规则算出来给业务看
    pttl = expAt > 0 and (expAt - now) or -1
  end
  return {code, created, total, used, pttl, oldTotal, oldUsed, totalChanged, ruleChanged, oldPttl, oldRule}
end

if op == 'delete' then
  if not exists then
    return ret(3)
  end
  redis.call('DEL', key)
  return ret(0)
end

-- 只读查看：不同步、不创建
if op == 'peek' then
  if not exists then
    return ret(3)
  end
  return ret(0)
end

if not exists then
  if op == 'restore' then
    return ret(3)
  end
  if op == 'get' then
    total = reqTotal
    return ret(0)
  end
  if expAt > 0 and expAt <= now then
    return ret(2)
  end
  total = reqTotal; used = 0; created = 1
  redis.call('HSET', key, 'total', total, 'used', 0, 'rule', rule)
  if expAt > 0 then
    redis.call('PEXPIREAT', key, expAt)
  end
else
  if reqTotal ~= total then
    total = reqTotal
    redis.call('HSET', key, 'total', total)
    totalChanged = 1
  end
  if rule ~= oldRule then
    ruleChanged = 1
    if expAt > 0 and expAt <= now then
      redis.call('DEL', key)
      return ret(2)
    end
    redis.call('HSET', key, 'rule', rule)
    if expAt > 0 then
      redis.call('PEXPIREAT', key, expAt)
    else
      redis.call('PERSIST', key)
    end
  end
end

if op == 'get' then
  return ret(0)
elseif op == 'consume' then
  if n <= 0 then
    return ret(6)
  end
  -- 总资格调小后已用可能超过总资格，这里只是扣不了，不算异常
  if used + n > total then
    return ret(1)
  end
  used = used + n
elseif op == 'restore' then
  if n <= 0 or n > used then
    return ret(6)
  end
  used = used - n
elseif op == 'setAvailable' then
  if n < 0 or n > total then
    return ret(6)
  end
  used = total - n
else
  return redis.error_reply('unknown op ' .. op)
end
redis.call('HSET', key, 'used', used)
return ret(0)
