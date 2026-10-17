package com.google.common.base;public final class Preconditions{public static void checkArgument(boolean ok,Object message){if(!ok)throw new IllegalArgumentException(String.valueOf(message));}}
