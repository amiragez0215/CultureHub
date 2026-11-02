package cn.culturehub;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class Access {
  final Store s;

  public Access(Store s) {
    this.s = s;
  }

  public long user() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !a.isAuthenticated() || a.getName().equals("anonymousUser"))
      throw new Problem("UNAUTHENTICATED", "Login required", 401);
    long id = Long.parseLong(a.getName());
    Problem.require(
        s.number("select count(*) from app_user where id=? and enabled=1", id) == 1,
        "ACCOUNT_DISABLED",
        "Account disabled");
    return id;
  }

  public boolean platform() {
    return s.number(
            "select count(*) from user_role where user_id=? and role='PLATFORM_ADMIN'", user())
        > 0;
  }

  public void platformOnly() {
    if (!platform()) throw new Problem("FORBIDDEN", "Platform administrator required", 403);
  }

  public void venue(long id) {
    long u = user();
    if (!platform()
        && s.number(
                "select count(*) from venue_grant g join user_role r on r.user_id=g.user_id and"
                    + " r.role='VENUE_ADMIN' where g.user_id=? and g.venue_id=?",
                u,
                id)
            == 0) throw new Problem("FORBIDDEN", "Venue not authorized", 403);
  }

  public void session(long id) {
    venue(
        s.number(
            "select a.venue_id from activity_session x join activity a on a.id=x.activity_id where"
                + " x.id=?",
            id));
  }

  public void owner(long id) {
    if (user() != id) throw new Problem("FORBIDDEN", "Not your record", 403);
  }
}
