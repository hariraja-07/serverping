# Graph Report - serverping  (2026-10-19)

## Corpus Check
- 5 files · ~1,445 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 25 nodes · 33 edges · 5 communities (3 shown, 2 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- mvnw
- ServerpingApplicationTests.java
- HelloController.java
- ServerpingApplication
- com.serverping:serverping

## God Nodes (most connected - your core abstractions)
1. `HelloController` - 3 edges
2. `ServerpingApplication` - 3 edges
3. `ServerpingApplicationTests` - 3 edges
4. `com.serverping:serverping` - 0 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Import Cycles
- None detected.

## Communities (5 total, 2 thin omitted)

### Community 0 - "mvnw"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 1 - "ServerpingApplicationTests.java"
Cohesion: 0.60
Nodes (3): org.junit.jupiter.api.Test, org.springframework.boot.test.context.SpringBootTest, ServerpingApplicationTests

### Community 2 - "HelloController.java"
Cohesion: 0.60
Nodes (3): org.springframework.web.bind.annotation.GetMapping, org.springframework.web.bind.annotation.RestController, HelloController

## Knowledge Gaps
- **1 isolated node(s):** `com.serverping:serverping`
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 3 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What connects `com.serverping:serverping` to the rest of the system?**
  _1 weakly-connected nodes found - possible documentation gaps or missing edges._