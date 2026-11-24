local group = 'booking'
local id = ARGV[1]
if ARGV[2] == 'OFFER' then
    if redis.call('TYPE', KEYS[1]).ok == 'none' then
        redis.call('XGROUP', 'CREATE', KEYS[1], group, '0', 'MKSTREAM')
    end
    redis.call('XADD', KEYS[1], '*', 'request', id, 'trace', ARGV[3])
    return {}
end
if redis.call('TYPE', KEYS[1]).ok ~= 'stream' then return {} end
if ARGV[2] == 'ACK' then
    redis.call('XACK', KEYS[1], group, id)
    redis.call('XDEL', KEYS[1], id)
    return {}
end
local claimed = redis.call('XAUTOCLAIM', KEYS[1], group, id, ARGV[3], '0-0', 'COUNT', 16)[2]
local fresh = redis.call('XREADGROUP', 'GROUP', group, id, 'COUNT', 16, 'STREAMS', KEYS[1], '>')
local flat = {}
local function append(entries)
    for _, entry in ipairs(entries) do
        local fields = {}
        for i = 1, #entry[2], 2 do fields[entry[2][i]] = entry[2][i + 1] end
        table.insert(flat, entry[1])
        table.insert(flat, fields.request or '')
        table.insert(flat, fields.epoch or '')
        table.insert(flat, fields.trace or '')
    end
end
append(claimed)
if fresh then append(fresh[1][2]) end
return flat
