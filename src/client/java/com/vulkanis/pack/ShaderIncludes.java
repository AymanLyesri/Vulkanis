package com.vulkanis.pack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code #include <vulkanis/<name>>} directives in pack GLSL by
 * inlining engine-shipped helper files. Packs own their shaders; includes
 * are the shared bits (Poisson tables, coordinate helpers) no pack should
 * have to hand-copy. Resolution is recursive with cycle and depth guards;
 * failures throw a named error instead of emitting half-built GLSL.
 */
public final class ShaderIncludes {
  public static final class IncludeException extends RuntimeException {
    public IncludeException(String message) {
      super(message);
    }
  }

  /** Loads an include body by file name (e.g. {@code poisson.glsl}), null when missing. */
  public interface Loader {
    String load(String name);
  }

  public static final int MAX_DEPTH = 8;
  private static final Pattern DIRECTIVE =
    Pattern.compile("(?m)^[ \\t]*#[ \\t]*include[ \\t]*<([^>\\s]+)>[ \\t]*\\r?\\n?");

  private ShaderIncludes() {
  }

  public static String resolve(String glsl, Loader loader) {
    return resolveInto(glsl, loader, new ArrayDeque<>());
  }

  private static String resolveInto(String glsl, Loader loader, Deque<String> stack) {
    Matcher m = DIRECTIVE.matcher(glsl);
    StringBuffer out = new StringBuffer();
    while (m.find()) {
      String path = m.group(1);
      if (!path.startsWith("vulkanis/")) {
        throw new IncludeException("include outside vulkanis/: " + path);
      }
      String name = path.substring("vulkanis/".length());
      if (name.isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) {
        throw new IncludeException("bad include path: " + path);
      }
      if (stack.contains(name)) {
        throw new IncludeException("cyclic include: " + name + " (via " + stack + ")");
      }
      if (stack.size() >= MAX_DEPTH) {
        throw new IncludeException("include depth exceeded at: " + name);
      }
      String body = loader.load(name);
      if (body == null) {
        throw new IncludeException("missing include: " + name);
      }
      stack.push(name);
      String resolved;
      try {
        resolved = resolveInto(body, loader, stack);
      } finally {
        stack.pop();
      }
      m.appendReplacement(out, Matcher.quoteReplacement(resolved));
    }
    m.appendTail(out);
    return out.toString();
  }
}
