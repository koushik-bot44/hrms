package com.ihrms.companies;

import com.ihrms.storage.StorageService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Deletes a purged company's actual stored objects (S3 objects / db blobs) AFTER the purge
 * transaction commits. Runs on commit only (never on rollback), and is best-effort — orphaned
 * objects are logged, never re-thrown, so storage can't undo the already-committed purge (§7).
 */
@Component
class CompanyPurgeStorageCleanup {

  private final StorageService storage;

  CompanyPurgeStorageCleanup(StorageService storage) {
    this.storage = storage;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onCompanyPurged(CompanyPurgedEvent event) {
    storage.deleteQuietly(event.storageKeys());
  }
}
