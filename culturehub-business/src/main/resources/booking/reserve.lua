if not state() then return 'UNSAFE' end
projectPriority()
local field = 'request:' .. ARGV[7]
local previous = redis.call('HGET', KEYS[1], field)
if previous then return previous end
local operation = ARGV[8]
local seats = tonumber(redis.call('HGET', KEYS[1], 'seats'))
local held = tonumber(redis.call('HGET', KEYS[1], 'held'))
local needHold = operation == 'DIRECT' or operation == 'PROMOTE' or operation == 'GROUP_HOLD'
if (operation == 'DIRECT' or operation == 'GROUP_HOLD')
    and redis.call('HGET', KEYS[1], 'waiting') == '1' then
    return 'WAITLIST_PRIORITY'
end
if needHold and seats <= held then return 'SESSION_FULL' end
if operation == 'JOIN' and seats > held and ARGV[9] == '0' then return 'SEATS_AVAILABLE' end

-- Validate the hash/stream and arithmetic before writing. Redis scripts do not
-- roll back successful writes when a later command fails.
-- One group command is relayed by Core. Individual group holds never dispatch a booking.
if operation == 'GROUP_HOLD' then
    local receipt = 'group:1'
    redis.call('HSET', KEYS[1], field, receipt, 'held', held + 1)
    return receipt
end
local id = redis.pcall('XADD', KEYS[2], '*', 'request', ARGV[7], 'epoch', ARGV[1], 'trace', ARGV[10])
if type(id) == 'table' then return 'UNSAFE' end
local receipt = id .. ':' .. (needHold and '1' or '0')
local saved = redis.pcall('HSET', KEYS[1], field, receipt, 'held', held + (needHold and 1 or 0))
if type(saved) == 'table' then
    redis.call('XDEL', KEYS[2], id)
    return 'UNSAFE'
end
return receipt
