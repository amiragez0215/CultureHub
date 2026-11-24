local before = -1
if kind(KEYS[1]) == 'hash' then
    local seats = tonumber(redis.call('HGET', KEYS[1], 'seats'))
    local held = tonumber(redis.call('HGET', KEYS[1], 'held'))
    if seats and held then before = math.max(0, seats - held) end
end
if ARGV[8] == 'REBUILD' and (kind(KEYS[1]) ~= 'hash'
    or redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[1]) then
    if kind(KEYS[1]) == 'hash' then
        local current = tonumber(redis.call('HGET', KEYS[1], 'revision'))
        if current and current > tonumber(ARGV[2]) then return {-1, -1} end
    end
    redis.call('DEL', KEYS[1], KEYS[2])
end
if not state() then return {-1, -1} end
project()
projectPriority()
local held = tonumber(redis.call('HGET', KEYS[1], 'held'))
if ARGV[7] ~= '' then
    local field = 'request:' .. ARGV[7]
    local receipt = redis.call('HGET', KEYS[1], field)
    if receipt and string.sub(receipt, -2) == ':1' then
        if held < 1 then return {-1, -1} end
        held = held - 1
    end
    redis.call('HSET', KEYS[1], field, 'CLOSED', 'held', held)
end
return {before, math.max(0, tonumber(redis.call('HGET', KEYS[1], 'seats')) - held)}
