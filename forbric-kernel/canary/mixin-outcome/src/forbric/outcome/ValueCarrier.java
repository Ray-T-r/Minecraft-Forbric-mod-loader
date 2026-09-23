package forbric.outcome;
public final class ValueCarrier {
 public static String apply(String value){return value;}
 public static String apply(String value,String context){return value+"|"+context;}
}
