package com.ihrms.offboarding;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The fixed structure of the HR-side offboarding Clearance form (§3.6 stage 2) — the single source of truth
 * for the frontend form, the stored item keys, and the PDF layout. Faithful to the source Clearance Form
 * (5 sections + the final IT sign-off + the final status). Two item kinds: YES_NO (yes/no + remarks) and
 * RETURNED (a single "returned" checkbox + remarks).
 */
public final class ClearanceSpec {

  private ClearanceSpec() {}

  public enum ItemKind {
    YES_NO,
    RETURNED
  }

  public record Item(String key, String label) {}

  public record Section(String key, String title, ItemKind kind, List<Item> items) {}

  /** The single yes/no sign-off that sits below the sections (its own key). */
  public static final String FINAL_IT_SIGNOFF_KEY = "final_it_signoff";
  public static final String FINAL_IT_SIGNOFF_LABEL =
      "Final IT Sign-Off: All access revoked and data verified as per policy";

  public static final List<Section> SECTIONS =
      List.of(
          new Section(
              "hr",
              "HR / Clearance",
              ItemKind.YES_NO,
              List.of(
                  new Item("resignation_termination", "Resignation/Termination"),
                  new Item("nda_reminder", "NDA & Confidentiality Reminder Given"),
                  new Item("exit_interview", "Exit Interview Completed"),
                  new Item("id_card_returned", "ID Card Returned"))),
          new Section(
              "it_access",
              "IT Access Deactivation",
              ItemKind.YES_NO,
              List.of(
                  new Item("email_disabled", "Email Account Disabled"),
                  new Item("vpn_revoked", "Network/VPN Access Revoked"),
                  new Item("system_access_revoked", "System & Application Access Revoked"),
                  new Item("admin_access_removed", "Admin/Privileged Access Removed"))),
          new Section(
              "it_assets",
              "IT Asset Return",
              ItemKind.RETURNED,
              List.of(
                  new Item("laptop", "Laptop/Desktop"),
                  new Item("mobile_device", "Mobile Device"),
                  new Item("access_cards", "Access Cards/Tokens"),
                  new Item("external_drives", "External Drives"))),
          new Section(
              "erm",
              "ERM Clearance",
              ItemKind.YES_NO,
              List.of(
                  new Item("consultant_verification", "Consultant Project verification"),
                  new Item(
                      "move_resumes",
                      "Move resumes, RTRs, and agreements to concern Manager/Team Member"),
                  new Item("notify_vendors", "Notify key vendors/clients of POC"),
                  new Item("audit_30_days", "Audit last 30 days Interview/Submissions"))),
          new Section(
              "data",
              "Data Verification",
              ItemKind.YES_NO,
              List.of(
                  new Item(
                      "data_local", "Consultants/Client/Vendor data stored in local system/Drives"),
                  new Item("no_personal_storage", "No Business Data on Personal Storage"),
                  new Item("kt_completed", "Knowledge Transfer Completed"),
                  new Item("ownership_files", "Ownership of Files"),
                  new Item("mailbox_archived", "Mailbox/Data Archived as per Policy"),
                  new Item("no_suspicious_transfer", "No Suspicious Data Transfer Detected"))));

  /** Every valid item key (section items + the final IT sign-off) — for validating stored/submitted values. */
  public static final Set<String> KEYS =
      Stream.concat(
              SECTIONS.stream().flatMap(s -> s.items().stream()).map(Item::key),
              Stream.of(FINAL_IT_SIGNOFF_KEY))
          .collect(Collectors.toUnmodifiableSet());
}
