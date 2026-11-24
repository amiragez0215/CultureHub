package cn.culturehub.v3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

public final class ServiceBoundary {
  public static final String CALLER = "X-CultureHub-Service";
  public static final String CREDENTIAL = "X-CultureHub-Service-Token";
  public static final String TRACE = "X-Trace-Id";
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
  private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

  private ServiceBoundary() {}

  public static String requireCredential(String value) {
    if (value == null || !TOKEN.matcher(value).matches()) {
      throw new IllegalArgumentException("A service credential must contain 32–128 safe ASCII characters");
    }
    return value;
  }

  public static boolean authenticates(String expected, String presented) {
    return expected != null && presented != null && TOKEN.matcher(presented).matches()
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            presented.getBytes(StandardCharsets.US_ASCII));
  }

  public static String traceId(String value) {
    return value != null && TRACE_ID.matcher(value).matches() ? value : UUID.randomUUID().toString();
  }

  public static boolean validUserId(String subject) {
    if (subject == null || !subject.matches("[1-9][0-9]{0,18}")) return false;
    try {
      return Long.parseLong(subject) > 0;
    } catch (NumberFormatException overflow) {
      return false;
    }
  }

  public static void verifyDatabase(DataSource dataSource, String schema, String user)
      throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery("select database(), substring_index(current_user(), '@', 1)")) {
      if (!result.next() || !schema.equals(result.getString(1)) || !user.equals(result.getString(2))) {
        throw new IllegalStateException("Service database identity does not match " + schema + "/" + user);
      }
    }
  }
}
