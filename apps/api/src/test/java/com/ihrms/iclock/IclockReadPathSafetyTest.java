package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STANDING RULE #2, enforced by DISCOVERY rather than by a list.
 *
 * <p>The rule: a read or preview path must be STRUCTURALLY unable to write. "Careful not to call
 * save()" is not a safety property — these services run inside a transaction, repository lookups return
 * MANAGED entities, and Hibernate dirty-checks and flushes at commit whether or not anyone asked. That
 * is how the roster import's "dry run writes nothing" contract became a silent rewrite of every person
 * it touched: the {@code commit} flag guarded the {@code save()} call, which was never the thing doing
 * the writing. {@code @Transactional(readOnly = true)} puts Hibernate in {@code FlushMode.MANUAL} and
 * marks the connection read-only, so an accidental write fails loudly instead of happening quietly.
 *
 * <p><b>This class used to iterate a hard-coded list of four services, and that was the defect.</b> A
 * new service was invisible to every rule here and the build stayed green with the rule unenforced —
 * the list had already drifted three services behind the code ({@code IclockPromotionService},
 * {@code IclockService}, {@code IclockStore} were never policed). It now scans the package, so a
 * service cannot opt out of the rule by being new.
 *
 * <p>No Spring context and no database: {@link ClassPathScanningCandidateComponentProvider} reads
 * bean definitions off the classpath, so this runs everywhere including CI, where the DB-gated suite
 * self-skips.
 */
class IclockReadPathSafetyTest {

  private static final String PACKAGE = "com.ihrms.iclock";

  /**
   * Method-name prefixes that denote a READ. A method starting with one of these must be read-only.
   *
   * <p>A convention rather than a list of methods, deliberately: a list is a thing that drifts, and
   * drift is what this class exists to prevent. {@code preview} is here because the preview/apply split
   * is the shape the rule forced on the two dual-mode imports, and a future {@code previewX} that
   * quietly runs read-write would reintroduce the exact bug.
   */
  private static final List<String> READ_PREFIXES =
      List.of("get", "list", "find", "preview", "load", "read", "fetch", "count", "search");

  /** Discovered once; every test asserts against the same inventory. */
  private static List<Class<?>> services() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
    List<Class<?>> out = new ArrayList<>();
    for (BeanDefinition bd : scanner.findCandidateComponents(PACKAGE)) {
      try {
        out.add(Class.forName(bd.getBeanClassName()));
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("scanned a class that will not load: " + bd.getBeanClassName(), e);
      }
    }
    out.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));
    return out;
  }

  private static List<Method> transactionalMethods(Class<?> service) {
    List<Method> out = new ArrayList<>();
    for (Method m : service.getDeclaredMethods()) {
      if (m.getAnnotation(Transactional.class) != null) {
        out.add(m);
      }
    }
    out.sort((a, b) -> a.getName().compareTo(b.getName()));
    return out;
  }

  @Test
  void theScanActuallyFindsTheModulesServices() {
    // A scan that silently finds nothing would make every other test in this class vacuous — the exact
    // failure mode the hard-coded list had, arrived at from the other direction.
    List<Class<?>> found = services();
    Set<String> names = new TreeSet<>();
    found.forEach(c -> names.add(c.getSimpleName()));

    assertThat(found).as("package scan of %s found no @Service classes", PACKAGE).isNotEmpty();
    assertThat(names)
        .as("the services the old hard-coded list omitted must now be covered")
        .contains(
            "IclockAdminService",
            "IclockBoardService",
            "IclockInboxService",
            "IclockRosterService",
            "IclockPromotionService");
  }

  /**
   * The ONE boolean that is allowed, named and justified rather than silently tolerated.
   *
   * <p>{@code IclockStore#upsertDevice(serialNumber, handshake, …)} writes on BOTH branches — the flag
   * chooses which liveness columns to touch, not whether to persist. It can never be {@code readOnly},
   * so it cannot carry the defect this ban exists to prevent. It is registered here, as a single named
   * exception, rather than by loosening the rule: anything new still fails, and this entry has to be
   * argued for in review the same way.
   *
   * <p>An exception register is not the hard-coded service list this class was rewritten to escape.
   * That list decided WHAT GETS CHECKED and drifted silently three services behind the code; this
   * decides what is known-safe, and everything is still checked.
   */
  private static final Set<String> ALLOWED_BOOLEANS = Set.of("IclockStore#upsertDevice");

  @Test
  void noTransactionalMethodTakesABoolean() {
    // The outlawed shape: one @Transactional method that either previews or writes depending on a flag.
    // Such a method cannot be readOnly, so its preview half is never structurally safe. Enforced across
    // EVERYTHING discovered, per the standing rule.
    List<String> offenders = new ArrayList<>();
    for (Class<?> service : services()) {
      for (Method m : transactionalMethods(service)) {
        String key = service.getSimpleName() + "#" + m.getName();
        if (ALLOWED_BOOLEANS.contains(key)) {
          continue;
        }
        for (Class<?> p : m.getParameterTypes()) {
          if (p == boolean.class || p == Boolean.class) {
            offenders.add(key + " — split it into preview…/apply… instead of branching on a flag");
          }
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  void everyAllowedBooleanStillExistsAndStillCannotBeReadOnly() {
    // An exception register decays into a lie if entries outlive the methods they excuse, or if the
    // method later becomes read-only — at which point the boolean IS the dangerous shape again.
    for (String key : ALLOWED_BOOLEANS) {
      String[] parts = key.split("#", 2);
      Class<?> service =
          services().stream()
              .filter(c -> c.getSimpleName().equals(parts[0]))
              .findFirst()
              .orElseThrow(() -> new AssertionError("stale exception — no such service: " + key));
      Method m =
          transactionalMethods(service).stream()
              .filter(x -> x.getName().equals(parts[1]))
              .findFirst()
              .orElseThrow(() -> new AssertionError("stale exception — no such method: " + key));
      assertThat(m.getAnnotation(Transactional.class).readOnly())
          .as("%s is excused the boolean ban only because it always writes", key)
          .isFalse();
    }
  }

  @Test
  void everyMethodThatReadsIsReadOnly() {
    List<String> offenders = new ArrayList<>();
    for (Class<?> service : services()) {
      for (Method m : transactionalMethods(service)) {
        String name = m.getName();
        boolean looksLikeARead =
            READ_PREFIXES.stream().anyMatch(p -> name.toLowerCase(Locale.ROOT).startsWith(p));
        if (looksLikeARead && !m.getAnnotation(Transactional.class).readOnly()) {
          offenders.add(
              service.getSimpleName() + "#" + name
                  + " — reads by its name but its transaction is writable");
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  void noReadOnlyMethodReturnsVoid() {
    // A read-only method returning nothing has no readable result, so it is either mislabelled or it
    // meant to write and cannot. Either way it is a bug rather than a style question.
    List<String> offenders = new ArrayList<>();
    for (Class<?> service : services()) {
      for (Method m : transactionalMethods(service)) {
        if (m.getAnnotation(Transactional.class).readOnly() && m.getReturnType() == void.class) {
          offenders.add(service.getSimpleName() + "#" + m.getName());
        }
      }
    }
    assertThat(offenders).as("read-only methods that return nothing").isEmpty();
  }

  @Test
  void theKnownReadPathsAreStillReadOnly() {
    // Belt and braces over the convention: these are the paths whose read-only-ness has been reasoned
    // about explicitly. The convention above would catch most of them, but not `inboxFor`, `unmapped`,
    // `board`, `overview`, `personDay` or `suggestionsFor`, whose names do not start with a read verb.
    record Expected(String service, String method) {}
    List<Expected> expected =
        List.of(
            new Expected("IclockRosterService", "inboxFor"),
            new Expected("IclockRosterService", "suggestionsFor"),
            new Expected("IclockBoardService", "overview"),
            new Expected("IclockBoardService", "board"),
            new Expected("IclockBoardService", "personDay"),
            new Expected("IclockInboxService", "unmapped"),
            new Expected("IclockAdminService", "siteDetail"));

    List<String> offenders = new ArrayList<>();
    for (Expected e : expected) {
      Class<?> service =
          services().stream()
              .filter(c -> c.getSimpleName().equals(e.service()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("service vanished from the scan: " + e.service()));
      Method m =
          transactionalMethods(service).stream()
              .filter(x -> x.getName().equals(e.method()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "expected a @Transactional " + e.service() + "#" + e.method()));
      if (!m.getAnnotation(Transactional.class).readOnly()) {
        offenders.add(e.service() + "#" + e.method());
      }
    }
    assertThat(offenders).isEmpty();
  }
}
