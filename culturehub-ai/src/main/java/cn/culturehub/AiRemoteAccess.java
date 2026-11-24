package cn.culturehub;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
class AiRemoteAccess extends Access {
 AiRemoteAccess(Store store){super(store);}
 static String bearer(){var auth=SecurityContextHolder.getContext().getAuthentication();if(!(auth instanceof JwtAuthenticationToken jwt))throw new Problem("UNAUTHENTICATED","Login required",401);return "Bearer "+jwt.getToken().getTokenValue();}
 @Override public long user(){bearer();return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());}
 @Override public boolean platform(){return false;}
 @Override public void platformOnly(){throw new Problem("FORBIDDEN","AI guide is read only",403);}
 @Override public void venue(long id){throw new Problem("FORBIDDEN","Use Knowledge Service authorization",403);}
}
