package lk.ijse.eca.studentservice.service.impl;

import lk.ijse.eca.studentservice.dto.StudentRequestDTO;
import lk.ijse.eca.studentservice.dto.StudentResponseDTO;
import lk.ijse.eca.studentservice.entity.Student;
import lk.ijse.eca.studentservice.mapper.StudentMapper;
import lk.ijse.eca.studentservice.exception.DuplicateStudentException;
import lk.ijse.eca.studentservice.exception.FileOperationException;
import lk.ijse.eca.studentservice.exception.StudentNotFoundException;
import lk.ijse.eca.studentservice.repository.StudentRepository;
import lk.ijse.eca.studentservice.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudentServiceImpl implements StudentService {

    private final StudentRepository studentRepository;
    private final StudentMapper studentMapper;

    @Value("${app.storage.path}")
    private String storagePathStr;

    private Path storagePath;

    /**
     * Creates a new student.
     *
     * Transaction strategy:
     *  1. Persist student record to DB (JPA defers the INSERT until flush/commit).
     *  2. Write picture file to disk (immediate).
     *  3. If the file write fails an exception is thrown, which causes
     *     @Transactional to roll back the DB INSERT — no orphaned record.
     *  4. If the file write succeeds the method returns normally and
     *     @Transactional commits both the record and the file atomically.
     */
    @Override
    @Transactional
    public StudentResponseDTO createStudent(StudentRequestDTO dto) {
        log.debug("Creating student with NIC: {}", dto.getNic());

        if (studentRepository.existsById(dto.getNic())) {
            log.warn("Duplicate NIC detected: {}", dto.getNic());
            throw new DuplicateStudentException(dto.getNic());
        }

        String pictureId = UUID.randomUUID().toString();

        Student student = studentMapper.toEntity(dto);
        student.setPicture(pictureId);

        // DB operation first (deferred) — rolls back if file save below throws
        studentRepository.save(student);
        log.debug("Student persisted to DB: {}", dto.getNic());

        // Immediate file operation — failure triggers @Transactional rollback
        savePicture(pictureId, dto.getPicture());

        log.info("Student created successfully: {}", dto.getNic());
        return studentMapper.toResponseDto(student);
    }

    /**
     * Updates an existing student.
     *
     * Transaction strategy:
     *  - If a new picture is supplied:
     *    1. Update DB record with new picture UUID (deferred).
     *    2. Write the new picture file (immediate).
     *    3. Failure at step 2 rolls back step 1 — old picture UUID stays in DB.
     *    4. On success, the old picture file is deleted (best-effort: a warning is
     *       logged on failure, but the transaction is NOT rolled back because DB and
     *       new file are already consistent).
     *  - If no new picture is supplied, only DB fields are updated.
     */
    @Override
    @Transactional
    public StudentResponseDTO updateStudent(String nic, StudentRequestDTO dto) {
        log.debug("Updating student with NIC: {}", nic);

        Student student = studentRepository.findById(nic)
                .orElseThrow(() -> {
                    log.warn("Student not found for update: {}", nic);
                    return new StudentNotFoundException(nic);
                });

        String oldPictureId = student.getPicture();
        boolean pictureChanged = dto.getPicture() != null && !dto.getPicture().isEmpty();
        String newPictureId = pictureChanged ? UUID.randomUUID().toString() : oldPictureId;

        studentMapper.updateEntity(dto, student);
        student.setPicture(newPictureId);

        // DB update (deferred) — rolls back if new file save below throws
        studentRepository.save(student);
        log.debug("Student updated in DB: {}", nic);

        if (pictureChanged) {
            // Save new picture — failure triggers @Transactional rollback
            savePicture(newPictureId, dto.getPicture());
            // Remove old picture — best-effort; DB and new file are already consistent
            tryDeletePicture(oldPictureId);
        }

        log.info("Student updated successfully: {}", nic);
        return studentMapper.toResponseDto(student);
    }

    /**
     * Deletes a student.
     *
     * Transaction strategy:
     *  1. Remove student record from DB (JPA defers the DELETE until flush/commit).
     *  2. Delete picture file from disk (immediate).
     *  3. If the file delete fails an exception is thrown, which causes
     *     @Transactional to roll back the DB DELETE — neither the record
     *     nor the file is removed.
     *  4. If the file delete succeeds the method returns normally and
     *     @Transactional commits, removing the record from the DB.
     */
    @Override
    @Transactional
    public void deleteStudent(String nic) {
        log.debug("Deleting student with NIC: {}", nic);

        Student student = studentRepository.findById(nic)
                .orElseThrow(() -> {
                    log.warn("Student not found for deletion: {}", nic);
                    return new StudentNotFoundException(nic);
                });

        String pictureId = student.getPicture();

        // DB deletion (deferred) — rolls back if file delete below throws
        studentRepository.delete(student);
        log.debug("Student marked for deletion in DB: {}", nic);

        // Immediate file deletion — failure triggers @Transactional rollback
        deletePicture(pictureId);

        log.info("Student deleted successfully: {}", nic);
    }

    @Override
    @Transactional(readOnly = true)
    public StudentResponseDTO getStudent(String nic) {
        log.debug("Fetching student with NIC: {}", nic);
        return studentRepository.findById(nic)
                .map(studentMapper::toResponseDto)
                .orElseThrow(() -> {
                    log.warn("Student not found: {}", nic);
                    return new StudentNotFoundException(nic);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<StudentResponseDTO> getAllStudents() {
        log.debug("Fetching all students");
        List<StudentResponseDTO> students = studentRepository.findAll()
                .stream()
                .map(studentMapper::toResponseDto)
                .peek(s -> s.setAddress(s.getAddress() + ", LK"))
                .collect(Collectors.toList());
        log.debug("Fetched {} students", students.size());
        return students;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] getStudentPicture(String nic) {
        log.debug("Fetching picture for student NIC: {}", nic);
        Student student = studentRepository.findById(nic)
                .orElseThrow(() -> {
                    log.warn("Student not found: {}", nic);
                    return new StudentNotFoundException(nic);
                });
        Path filePath = storagePath().resolve(student.getPicture());
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("Failed to read picture for student: {}", nic, e);
            throw new FileOperationException("Failed to read picture for student: " + nic, e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Path storagePath() {
        if (storagePath == null) {
            storagePath = Paths.get(storagePathStr);
        }
        try {
            Files.createDirectories(storagePath);
        } catch (IOException e) {
            throw new FileOperationException(
                    "Failed to create storage directory: " + storagePath.toAbsolutePath(), e);
        }
        return storagePath;
    }

    private void savePicture(String pictureId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileOperationException("Picture file must not be empty");
        }
        Path filePath = storagePath().resolve(pictureId);
        try {
            Files.write(filePath, file.getBytes());
            log.debug("Picture saved: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to save picture: {}", filePath, e);
            throw new FileOperationException("Failed to save picture file: " + pictureId, e);
        }
    }

    private void deletePicture(String pictureId) {
        Path filePath = storagePath().resolve(pictureId);
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                log.debug("Picture deleted: {}", filePath);
            } else {
                log.warn("Picture file not found on disk (already removed?): {}", filePath);
            }
        } catch (IOException e) {
            log.error("Failed to delete picture: {}", filePath, e);
            throw new FileOperationException("Failed to delete picture file: " + pictureId, e);
        }
    }

    private void tryDeletePicture(String pictureId) {
        try {
            deletePicture(pictureId);
        } catch (FileOperationException e) {
            log.warn("Could not delete old picture file '{}'. Manual cleanup may be required.", pictureId);
        }
    }

}
