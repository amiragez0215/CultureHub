package cn.culturehub;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  final Store s;
  final Access a;
  final Management m;
  final Booking b;
  final ParticipationService p;
  final CheckInService c;
  final Operations o;
  final PublicSessions publicSessions;
  final CatalogQueryMapper queries;
  final CatalogReadService reads;

  public ApiController(
      Store s,
      Access a,
      Management m,
      Booking b,
      ParticipationService p,
      CheckInService c,
      Operations o,
      PublicSessions publicSessions,
      CatalogQueryMapper queries,
      CatalogReadService reads) {
    this.s = s;
    this.a = a;
    this.m = m;
    this.b = b;
    this.p = p;
    this.c = c;
    this.o = o;
    this.publicSessions = publicSessions;
    this.queries = queries;
    this.reads = reads;
  }

  @GetMapping("/venues")
  Object venues() {
    return m.venueEntities();
  }

  @GetMapping("/venues/{id}")
  Object venueDetail(@PathVariable long id) {
    return s.one("select * from venue where id=?", id);
  }

  @GetMapping("/activities")
  Object activities(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String category,
      @RequestParam(defaultValue = "0") long venueId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return m.activities(false, q, category, page, venueId, "ACTIVE", from, to);
  }

  @GetMapping("/activities/{id}")
  Object activity(@PathVariable long id) {
    return reads.published(id);
  }

  @GetMapping("/activities/count")
  Object activityCount(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String category,
      @RequestParam(defaultValue = "0") long venueId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return Map.of("total", m.activityCount(false, q, category, venueId, "ACTIVE", from, to));
  }

  @GetMapping("/activities/{id}/sessions")
  Object sessions(@PathVariable long id) {
    return publicSessions.list(id);
  }

  @PostMapping("/sessions/{id}/reservations")
  Object reserve(@PathVariable long id, @RequestBody Map<String, String> v) {
    return b.create(id, v.get("request_id"), false);
  }

  @PostMapping("/sessions/{id}/waitlist")
  Object waitlist(@PathVariable long id, @RequestBody Map<String, String> v) {
    return b.create(id, v.get("request_id"), true);
  }

  @GetMapping("/me/participations")
  Object mine(@RequestParam(defaultValue = "1") int page) {
    return queries.history(a.user(), Inputs.offset(page));
  }

  @GetMapping("/me/participations/by-request/{request}")
  Object request(@PathVariable String request) {
    return s.one(
        "select id,session_id,status,confirm_deadline from participation where user_id=? and"
            + " request_id=?",
        a.user(),
        request);
  }

  @GetMapping("/me/participations/count")
  Object participationCount() {
    return Map.of(
        "total", s.number("select count(*) from participation where user_id=?", a.user()));
  }

  @GetMapping("/participations/{id}")
  Object participation(@PathVariable long id) {
    var v =
        s.one(
            "select id,user_id,session_id,status,confirm_deadline,current_timestamp server_time"
                + " from participation where id=?",
            id);
    a.owner(Store.id(v, "user_id"));
    return v;
  }

  @PostMapping("/participations/{id}/cancel")
  Object cancel(@PathVariable long id) {
    return p.cancel(id);
  }

  @PostMapping("/participations/{id}/confirm")
  Object confirm(@PathVariable long id) {
    return p.confirm(id);
  }

  @GetMapping("/participations/{id}/checkin-status")
  Object checkinStatus(@PathVariable long id) {
    return c.status(id);
  }

  @GetMapping("/participations/{id}/credential")
  Object credential(@PathVariable long id) {
    return c.credential(id);
  }

  @GetMapping("/me/notifications")
  Object notifications(@RequestParam(defaultValue = "0") long before) {
    return s.db.queryForList(
        "select * from notification where user_id=? and (?=0 or id<?) order by id desc limit 50",
        a.user(),
        before,
        before);
  }

  @PatchMapping("/me/notifications/{id}/read")
  Object read(@PathVariable long id) {
    s.db.update(
        "update notification set read_at=coalesce(read_at,current_timestamp) where id=? and"
            + " user_id=?",
        id,
        a.user());
    return Map.of("ok", true);
  }

  @PutMapping("/sessions/{id}/my-review")
  Object review(@PathVariable long id, @RequestBody Map<String, Object> v) {
    o.review(id, (int) Inputs.number(v, "rating"), Inputs.text(v, "content", 2000));
    return Map.of("ok", true);
  }

  @GetMapping("/sessions/{id}/my-review")
  Object myReview(@PathVariable long id) {
    var existing =
        s.db.queryForList(
            "select rating,content from review where user_id=? and session_id=?", a.user(), id);
    return existing.isEmpty() ? Map.of("rating", 5, "content", "") : existing.get(0);
  }

  @GetMapping("/sessions/{id}/reviews")
  Object reviews(@PathVariable long id) {
    return s.db.queryForList(
        "select rating,content,created_at from review where session_id=? order by id desc limit"
            + " 100",
        id);
  }

  @GetMapping("/admin/me/venues")
  Object adminVenues() {
    return m.mine();
  }

  @PostMapping("/admin/venues")
  Object saveVenue(@RequestBody Map<String, Object> v) {
    return Map.of("id", m.venue(v));
  }

  @GetMapping("/admin/users")
  Object users() {
    a.platformOnly();
    return s.db.queryForList(
        "select u.id,u.username,u.nickname,u.enabled,exists(select 1 from user_role r where"
            + " r.user_id=u.id and r.role='PLATFORM_ADMIN') platform_admin from app_user u order by"
            + " u.id limit 100");
  }

  @GetMapping("/admin/users/{id}/venue-grants")
  Object venueGrants(@PathVariable long id) {
    a.platformOnly();
    s.one("select id from app_user where id=?", id);
    return Map.of(
        "user_id",
        id,
        "venue_ids",
        s.db.queryForList(
            "select venue_id from venue_grant where user_id=? order by venue_id", Long.class, id));
  }

  @PutMapping("/admin/users/{id}/venue-grants")
  Object grant(@PathVariable long id, @RequestBody List<Long> venues) {
    m.grant(id, venues);
    return Map.of("ok", true);
  }

  @GetMapping("/admin/activities")
  Object adminActivities(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") long venueId,
      @RequestParam(defaultValue = "ACTIVE") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return m.activities(true, q, "", page, venueId, status, from, to);
  }

  @GetMapping("/admin/activities/count")
  Object adminActivityCount(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") long venueId,
      @RequestParam(defaultValue = "ACTIVE") String status,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return Map.of("total", m.activityCount(true, q, "", venueId, status, from, to));
  }

  @PostMapping("/admin/activities")
  Object saveActivity(@RequestBody Map<String, Object> v) {
    return Map.of("id", m.activity(v));
  }

  @PostMapping("/admin/activities/{id}/publish")
  Object publish(@PathVariable long id) {
    m.publish(id);
    return Map.of("ok", true);
  }

  @PostMapping("/admin/activities/{id}/cancel")
  Object cancelActivity(@PathVariable long id) {
    m.cancel(id);
    return Map.of("ok", true);
  }

  @GetMapping("/admin/activities/{id}/sessions")
  Object adminSessions(@PathVariable long id) {
    a.venue(s.number("select venue_id from activity where id=?", id));
    return reads.sessions(id);
  }

  @PostMapping("/admin/sessions")
  Object saveSession(@RequestBody Map<String, Object> v) {
    return Map.of("id", m.session(v));
  }

  @PostMapping("/admin/sessions/{id}/cancel")
  Object cancelSession(@PathVariable long id) {
    p.cancelSession(id);
    return Map.of("ok", true);
  }

  @GetMapping("/admin/sessions/{id}/participations")
  Object adminParticipation(@PathVariable long id) {
    a.session(id);
    return s.db.queryForList(
        "select p.id,p.user_id,u.username,p.status,p.queue_seq,p.confirm_deadline from"
            + " participation p join app_user u on u.id=p.user_id where p.session_id=? order by"
            + " p.id limit 100",
        id);
  }

  @PostMapping("/admin/check-ins")
  Object check(@RequestBody Map<String, Object> v) {
    return c.check(Store.id(v, "session_id"), Inputs.text(v, "token", 100));
  }

  @GetMapping("/admin/sessions/{id}/statistics")
  Object stats(@PathVariable long id) {
    return o.stats(id);
  }

  @GetMapping("/admin/sessions/{id}/reviews")
  Object adminReviews(@PathVariable long id) {
    a.session(id);
    return reads.reviews(id);
  }

  @GetMapping("/admin/statistics")
  Object statistics(
      @RequestParam long venueId, @RequestParam String from, @RequestParam String to) {
    return o.dashboard(venueId, from, to);
  }
}
