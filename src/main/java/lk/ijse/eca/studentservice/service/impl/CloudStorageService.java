package lk.ijse.eca.studentservice.service.impl;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@Service
public class CloudStorageService {

    @Value("${app.storage.bucket-name}")
    private String bucketName;

    private final Storage storage = StorageOptions.getDefaultInstance().getService();

    public String uploadFile(MultipartFile file, String nic) throws IOException {
        String fileName = "profile_pictures/" + nic;
        BlobId blobId = BlobId.of(bucketName, fileName);

        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(file.getContentType())
                .build();

        storage.create(blobInfo, file.getBytes());
        return String.format("https://storage.googleapis.com/%s/%s", bucketName, fileName);
    }

    public byte[] downloadFile(String nic) {
        String fileName = "profile_pictures/" + nic;
        BlobId blobId = BlobId.of(bucketName, fileName);
        return storage.readAllBytes(blobId);
    }

    public void deleteFile(String nic) {
        String fileName = "profile_pictures/" + nic;
        BlobId blobId = BlobId.of(bucketName, fileName);
        storage.delete(blobId);
    }
}