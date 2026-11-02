package cn.culturehub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(10)
public class Seed implements CommandLineRunner {
  final Store s;
  final PasswordEncoder encoder;
  final String password;

  public Seed(Store s, PasswordEncoder e, @Value("${culture.demo.password:}") String p) {
    this.s = s;
    encoder = e;
    password = p;
  }

  public void run(String... args) {
    if (password.isBlank()) return;
    for (String name : new String[] {"admin", "venueadmin", "user"}) {
      if (s.number("select count(*) from app_user where username=?", name) > 0) continue;
      long id =
          s.insert(
              "insert into app_user(username,password_hash,nickname) values(?,?,?)",
              name,
              encoder.encode(password),
              name);
      s.db.update(
          "insert into user_role values(?,?)",
          id,
          name.equals("admin")
              ? "PLATFORM_ADMIN"
              : name.equals("venueadmin") ? "VENUE_ADMIN" : "USER");
      if (name.equals("venueadmin"))
        s.db.update(
            "insert into venue_grant(user_id,venue_id) select ?,id from venue where id=1", id);
    }
  }
}
