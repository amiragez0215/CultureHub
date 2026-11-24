package cn.culturehub.v3;
import io.micrometer.tracing.*;
/** W3C context fits the existing 64-character trace field; old UUID values remain accepted. */
public final class TraceCarrier {
 private TraceCarrier(){}
 public static String capture(Tracer tracer,String fallback){
  var span=tracer==null?null:tracer.currentSpan();
  if(span==null)return ServiceBoundary.traceId(fallback);
  var context=span.context();return "00-"+context.traceId()+"-"+context.spanId()+"-"+(Boolean.TRUE.equals(context.sampled())?"01":"00");
 }
 public static Span start(Tracer tracer,String parent,String name){
  if(tracer==null)return null;
  var builder=tracer.spanBuilder().name(name);
  if(parent!=null&&parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")){
   var parts=parent.split("-");builder.setParent(tracer.traceContextBuilder().traceId(parts[1]).spanId(parts[2]).sampled(parts[3].equals("01")).build());
  }
  return builder.start();
 }
}
