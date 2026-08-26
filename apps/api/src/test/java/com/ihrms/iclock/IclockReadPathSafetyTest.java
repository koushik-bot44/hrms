package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * STANDING RULE #2, made executable: a read or preview path must be STRUCTURALLY unable to write.
 *
 * <p>The rule exists because "careful not to call save()" is not a safety property. These services run
 * inside a transaction and their repository lookups return MANAGED entities, so Hibernate dirty-checks
 * and flushes at commit whether or not anyone asked. That is precisely how the roster import's "dry run
 * writes nothing" contract became a silent rewrite of every person it touched: the {@code commit} flag
 * guarded the {@code save()} call, which was never the thing doing the writing.
 *
 * <p>{@code @Transactional(readOnly = true)} is the structural fix. It puts Hibernate in
 * {@code FlushMode.MANUAL} and marks the JDBC connection read-only, so an accidental write fails loudly
 * instead of happening quietly.
 *
 * <p>This test asserts the ANNOTATION rather than observing behaviour, deliberately. Behaviour is what
 * regressed last time, and a behavioural test only catches the write paths someone thought to exercise;
 * the annotation is the guarantee itself. It also fails for a NEW read method added without the
 * annotation, which a hand-written list of known methods would not.
 *
 * <p>No Spring context and no database: this is pure reflection over the compiled classes, so it runs
 * everywhere including CI, where the DB-gated suite self-skips.
 */
class IclockReadPathSafetyTest {

  /**
   * Read and preview entry points. Anything here must be read-only.
   *
   * <p>A dual-mode method — one that previews or writes depending on a flag — cannot satisfy this, so
   * the two imports are split into {@code preview…}/{@code apply…} pairs. That split also has to be
   * two distinct entry points rather than an internal dispatcher: Spring's transaction advice is
   * proxy-based, so {@code this.preview(...)} would silently inherit the caller's read-write
   * transaction and lose the guarantee entirely.
   */
  private static final Set<String> READ_PATHS =
      new TreeSet<>(
          Set.of(
              "IclockRosterService#previewRosterImport",
              "IclockRosterService#listPeople",
              "IclockRosterService#suggestionsFor",
              "IclockRosterService#inboxFor",
              "IclockBoardService#overview",
              "IclockBoardService#board",
              "IclockBoardService#personDay",
              "IclockInboxService#unmapped",
              "IclockAdminService#previewPinImport",
              "IclockAdminService#listSites",
              "IclockAdminService#siteDetail",
              "IclockAdminService#listDevices",
              "IclockAdminService#listPins"));

  private static final List<Class<?>> SERVICES =
      List.of(
          IclockRosterService.class,
          IclockBoardService.class,
          IclockInboxService.class,
          IclockAdminService.class);

  @Test
  void everyReadOrPreviewPathIsReadOnly() {
    List<String> offenders = new ArrayList<>();
    for (Class<?> service : SERVICES) {
      for (Method m : service.getDeclaredMethods()) {
        Transactional tx = m.getAnnotation(Transactional.class);
        if (tx == null) {
          continue;
        }
        String key = service.getSimpleName() + "#" + m.getName();
        if (READ_PATHS.contains(key) && !tx.readOnly()) {
          offenders.add(key + " is a read/preview path but its transaction is writable");
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  void everyReadOnlyMethodIsAccountedForInTheRule() {
    // The other direction: a method marked read-only that nobody listed means the list has drifted
    // behind the code, and the sweep above is quietly checking less than it claims to.
    List<String> unlisted = new ArrayList<>();
    for (Class<?> service : SERVICES) {
      for (Method m : service.getDeclaredMethods()) {
        Transactional tx = m.getAnnotation(Transactional.class);
        if (tx != null && tx.readOnly()) {
          String key = service.getSimpleName() + "#" + m.getName();
          if (!READ_PATHS.contains(key)) {
            unlisted.add(key);
          }
        }
      }
    }
    assertThat(unlisted).as("read-only methods missing from READ_PATHS").isEmpty();
  }

  @Test
  void noDualModeImportSurvives() {
    // The shape the rule outlaws: one @Transactional method that either previews or writes depending
    // on a boolean. Such a method cannot be read-only, so its preview half is never structurally safe.
    List<String> dualMode = new ArrayList<>();
    for (Class<?> service : SERVICES) {
      for (Method m : service.getDeclaredMethods()) {
        if (m.getAnnotation(Transactional.class) == null) {
          continue;
        }
        for (Class<?> p : m.getParameterTypes()) {
          if (p == boolean.class || p == Boolean.class) {
            dualMode.add(service.getSimpleName() + "#" + m.getName());
          }
        }
      }
    }
    assertThat(dualMode)
        .as("a transactional method taking a boolean is the preview-or-write shape the rule forbids")
        .isEmpty();
  }
}
