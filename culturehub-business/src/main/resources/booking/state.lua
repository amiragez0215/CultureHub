-- Both keys belong to the same session hash slot. A missing initialized key is
-- treated as loss, never as a reason to reset stock during admission.
local function kind(key)
    return redis.call('TYPE', key).ok
end

local function state()
    local h, s = kind(KEYS[1]), kind(KEYS[2])
    if h == 'none' and s == 'none' and ARGV[6] == '1' then
        redis.call('XGROUP', 'CREATE', KEYS[2], 'booking', '0', 'MKSTREAM')
        redis.call('HSET', KEYS[1], 'epoch', ARGV[1], 'revision', ARGV[2],
            'seats', ARGV[3], 'waiting', ARGV[4], 'waiting_revision', ARGV[2], 'held', '0')
        redis.call('EXPIREAT', KEYS[1], ARGV[5])
        redis.call('EXPIREAT', KEYS[2], ARGV[5])
    end
    if kind(KEYS[1]) ~= 'hash' or kind(KEYS[2]) ~= 'stream' then return false end
    if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[1] then return false end
    local revision = tonumber(redis.call('HGET', KEYS[1], 'revision'))
    local seats = tonumber(redis.call('HGET', KEYS[1], 'seats'))
    local held = tonumber(redis.call('HGET', KEYS[1], 'held'))
    if not revision or not seats or not held or held < 0 or seats < 0 then return false end
    return true
end

local function project()
    if tonumber(ARGV[2]) >= tonumber(redis.call('HGET', KEYS[1], 'revision')) then
        redis.call('HSET', KEYS[1], 'revision', ARGV[2], 'seats', ARGV[3])
    end
end

local function projectPriority()
    local version = tonumber(redis.call('HGET', KEYS[1], 'waiting_revision')) or -1
    if tonumber(ARGV[2]) >= version then
        redis.call('HSET', KEYS[1], 'waiting_revision', ARGV[2], 'waiting', ARGV[4])
    end
end
