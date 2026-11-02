package cn.culturehub;

public class Problem extends RuntimeException {
  public final String code;
  public final int status;

  public Problem(String code, String text) {
    this(code, text, 409);
  }

  public Problem(String code, String text, int status) {
    super(text);
    this.code = code;
    this.status = status;
  }

  public static void require(boolean ok, String code, String text) {
    if (!ok) throw new Problem(code, text);
  }
}
