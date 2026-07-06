package com.ihrms.companies;

import java.util.List;

/**
 * Published inside the {@link CompaniesService#purge} transaction with every stored object key that
 * belonged to the company. Consumed AFTER the transaction commits to delete the actual bytes
 * (S3 objects / db blobs); see {@link CompanyPurgeStorageCleanup}.
 */
public record CompanyPurgedEvent(String companyId, List<String> storageKeys) {}
