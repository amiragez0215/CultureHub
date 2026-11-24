-- Optional synthetic Partner fixture. Apply only to partner_db_a / partner_db_b in controlled demo mode.
-- The marker makes repeated execution add no duplicate fixture.
INSERT INTO venue(name,introduction) SELECT 'Partner 组合预约演示场馆','独立外部场馆模拟数据，用于 Seata AT 组合预约验证'
 WHERE NOT EXISTS(SELECT 1 FROM venue WHERE name='Partner 组合预约演示场馆');
INSERT INTO activity(venue_id,title,description,category,audience,status)
 SELECT v.id,'外部场馆传统工艺体验','本活动仅供受控的跨服务预留、确认和补偿演示，不进入正式活动目录。','HERITAGE','ALL','PUBLISHED'
 FROM venue v WHERE v.name='Partner 组合预约演示场馆'
 AND NOT EXISTS(SELECT 1 FROM activity a WHERE a.venue_id=v.id AND a.title='外部场馆传统工艺体验');
INSERT INTO activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status)
 SELECT a.id,date_sub(now(),interval 1 day),date_add(now(),interval 1 day),
 date_add(date_add(curdate(),interval 2 day),interval 14 hour),date_add(date_add(curdate(),interval 2 day),interval 15 hour),
 date_add(date_add(curdate(),interval 2 day),interval 13 hour),date_add(date_add(curdate(),interval 2 day),interval 15 hour),20,'PUBLISHED'
 FROM activity a WHERE a.title='外部场馆传统工艺体验' AND NOT EXISTS(SELECT 1 FROM activity_session x WHERE x.activity_id=a.id);
