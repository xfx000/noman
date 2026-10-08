package dev.qiqi.dataagent.agent;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.IOException;

/** Immutable bundled resources; activation remains local to each fresh agent. */
@Component
public class AnalysisSkills {
    private final ClasspathSkillRepository repository;
    private final ClasspathSkillRepository creativeRepository;
    public AnalysisSkills() throws IOException {
        repository = new ClasspathSkillRepository("skills");
        creativeRepository = new ClasspathSkillRepository("creative-skills");
    }
    public ClasspathSkillRepository repository() { return repository; }
    public ClasspathSkillRepository creativeRepository() { return creativeRepository; }
    @PreDestroy public void close() { repository.close(); creativeRepository.close(); }
}
