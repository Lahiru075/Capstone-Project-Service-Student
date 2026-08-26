package lk.ijse.eca.studentservice.service.impl;

import lk.ijse.eca.studentservice.dto.StudentRequestDTO;
import lk.ijse.eca.studentservice.dto.StudentResponseDTO;
import lk.ijse.eca.studentservice.entity.Student;
import lk.ijse.eca.studentservice.mapper.StudentMapper;
import lk.ijse.eca.studentservice.exception.FileOperationException;
import lk.ijse.eca.studentservice.exception.StudentNotFoundException;
import lk.ijse.eca.studentservice.repository.StudentRepository;
import lk.ijse.eca.studentservice.service.CloudStorageService;
import lk.ijse.eca.studentservice.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudentServiceImpl implements StudentService {

    private final StudentRepository studentRepository;
    private final CloudStorageService cloudStorageService;
    private final StudentMapper studentMapper;

    @Override
    @Transactional
    public StudentResponseDTO createStudent(StudentRequestDTO dto) {
        log.debug("Creating student with NIC: {}", dto.getNic());

        String pictureUrl = null;

        if (dto.getPicture() != null && !dto.getPicture().isEmpty()) {
            try {
                pictureUrl = cloudStorageService.uploadFile(dto.getPicture(), dto.getNic());
            } catch (IOException e) {
                log.error("Failed to upload picture for NIC: {}", dto.getNic(), e);
                throw new FileOperationException("Image upload failed for NIC: " + dto.getNic(), e);
            }
        }

        // 2. Map and Save to Database
        Student student = new Student();
        student.setNic(dto.getNic());
        student.setName(dto.getName());
        student.setAddress(dto.getAddress());
        student.setMobile(dto.getMobile());
        student.setEmail(dto.getEmail());

        student.setPicture(pictureUrl);

        Student savedStudent = studentRepository.save(student);
        return studentMapper.toResponseDto(savedStudent);
    }

    @Override
    @Transactional
    public StudentResponseDTO updateStudent(String nic, StudentRequestDTO dto) {
        log.debug("Updating student with NIC: {}", nic);

        Student student = studentRepository.findById(nic)
                .orElseThrow(() -> {
                    log.warn("Student not found for update: {}", nic);
                    return new StudentNotFoundException(nic);
                });

        if (dto.getPicture() != null && !dto.getPicture().isEmpty()) {
            try {
                String updatedPictureUrl = cloudStorageService.uploadFile(dto.getPicture(), nic);
                student.setPicture(updatedPictureUrl);
            } catch (IOException e) {
                log.error("Failed to update picture for NIC: {}", nic, e);
                throw new FileOperationException("Image update failed for NIC: " + nic, e);
            }
        }

        studentMapper.updateEntity(dto, student);

        studentRepository.save(student);
        log.info("Student updated successfully: {}", nic);

        return studentMapper.toResponseDto(student);
    }

    @Override
    @Transactional
    public void deleteStudent(String nic) {
        log.debug("Deleting student with NIC: {}", nic);

        Student student = studentRepository.findById(nic)
                .orElseThrow(() -> {
                    log.warn("Student not found for deletion: {}", nic);
                    return new StudentNotFoundException(nic);
                });

        // 1. Delete from DB
        studentRepository.delete(student);
        log.debug("Student deleted from DB: {}", nic);

        // 2. Delete from Cloud Storage
        try {
            cloudStorageService.deleteFile(nic);
            log.debug("Picture deleted from Cloud Storage: {}", nic);
        } catch (Exception e) {
            log.warn("Could not delete picture from Cloud Storage for NIC: {}. It may have been already deleted.", nic);
        }

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
    public byte[] getStudentPicture(String nic) {
        log.debug("Fetching picture from Cloud Storage for student NIC: {}", nic);

        studentRepository.findById(nic).orElseThrow(() -> new StudentNotFoundException(nic));

        try {
            return cloudStorageService.downloadFile(nic);
        } catch (Exception e) {
            log.error("Failed to read picture from Cloud Storage for student: {}", nic, e);
            throw new FileOperationException("Failed to read picture for student: " + nic, e);
        }
    }
}
