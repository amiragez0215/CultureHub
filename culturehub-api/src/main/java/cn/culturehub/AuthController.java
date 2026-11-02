package cn.culturehub;

import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  final Store s;
  final PasswordEncoder p;
  final JwtEncoder jwt;
  final Access a;

  public AuthController(Store s, PasswordEncoder p, JwtEncoder j, Access a) {
    this.s = s;
    this.p = p;
    jwt = j;
    this.a = a;
  }

  @PostMapping("/register")
  @Transactional
  public Object register(@RequestBody Map<String, Object> m) {
    String name = Management.text(m, "username", 40).toLowerCase(Locale.ROOT),
        password = Management.text(m, "password", 72);
    Problem.require(
        name.matches("[a-z0-9_]{3,40}")
            && password.length() >= 3
            && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72,
        "INVALID_ACCOUNT",
        "Username 3-40 letters/digits/underscore, password 3-72 characters");
    long id =
        s.insert(
            "insert into app_user(username,password_hash,nickname) values(?,?,?)",
            name,
            p.encode(password),
            name);
    s.db.update("insert into user_role(user_id,role) values(?,'USER')", id);
    return Map.of("id", id);
  }

  @PostMapping("/login")
  public Object login(@RequestBody Map<String, String> m) {
    var us =
        s.db.queryForList(
            "select * from app_user where username=? and enabled=1",
            Inputs.text(m, "username", 40).toLowerCase(Locale.ROOT));
    if (us.isEmpty()
        || !p.matches(Inputs.text(m, "password", 72), us.get(0).get("password_hash").toString()))
      throw new Problem("BAD_CREDENTIALS", "Invalid credentials", 401);
    long id = Store.id(us.get(0), "id");
    var now = java.time.Instant.now();
    String token =
        jwt.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(),
                    JwtClaimsSet.builder()
                        .subject("" + id)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(7200))
                        .issuer("culturehub")
                        .build()))
            .getTokenValue();
    return Map.of("token", token, "id", id);
  }

  @GetMapping("/me")
  public Object me() {
    long id = a.user();
    return Map.of(
        "user",
        s.one("select id,username,nickname from app_user where id=?", id),
        "roles",
        s.db.queryForList("select role from user_role where user_id=?", String.class, id));
  }
}
