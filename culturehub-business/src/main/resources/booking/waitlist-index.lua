if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[1]
    or redis.call('HGET', KEYS[1], 'revision') ~= ARGV[2] then return 0 end
redis.call('DEL', KEYS[2])
for i = 4, #ARGV, 2 do
    redis.call('ZADD', KEYS[2], ARGV[i], ARGV[i + 1])
end
redis.call('EXPIREAT', KEYS[2], ARGV[3])
return 1
