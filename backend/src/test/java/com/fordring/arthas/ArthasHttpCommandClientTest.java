package com.fordring.arthas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fordring.config.FordringProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArthasHttpCommandClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArthasHttpCommandClient client = new ArthasHttpCommandClient(
            objectMapper,
            new FordringProperties(),
            null
    );

    @Test
    void formatsJvmModelInfoGroupsAsText() throws Exception {
        var result = format("""
                {
                  "type": "jvm",
                  "jvmInfo": {
                    "RUNTIME": [
                      { "name": "MACHINE-NAME", "value": "12345@app" },
                      { "name": "VM-NAME", "value": "OpenJDK 64-Bit Server VM" }
                    ],
                    "CLASS-LOADING": [
                      { "name": "LOADED-CLASS-COUNT", "value": "9281" }
                    ],
                    "MEMORY": [
                      { "name": "HEAP-MEMORY-USAGE", "value": "used=128M, committed=256M" }
                    ],
                    "GARBAGE-COLLECTORS": [
                      {
                        "name": "G1 Young Generation",
                        "value": {
                          "name": "G1 Young Generation",
                          "collectionCount": 124,
                          "collectionTime": 4940
                        }
                      }
                    ],
                    "MEMORY-MANAGERS": [
                      {
                        "name": "CodeCacheManager",
                        "value": [
                          "CodeHeap 'non-nmethods'",
                          "CodeHeap 'profiled nmethods'",
                          "CodeHeap 'non-profiled nmethods'"
                        ]
                      }
                    ]
                  }
                }
                """);

        assertTrue(result.contains("JVM"));
        assertTrue(result.contains("Runtime"));
        assertTrue(result.contains("MACHINE-NAME"));
        assertTrue(result.contains("12345@app"));
        assertTrue(result.contains("Class Loading"));
        assertTrue(result.contains("LOADED-CLASS-COUNT"));
        assertTrue(result.contains("Garbage Collectors"));
        assertTrue(result.contains("collectionCount=124, collectionTime=4940"));
        assertTrue(result.contains("Memory Managers"));
        assertTrue(result.contains("CodeHeap 'non-nmethods', CodeHeap 'profiled nmethods', CodeHeap 'non-profiled nmethods'"));
        assertFalse(result.contains("\"jvmInfo\""));
        assertFalse(result.contains("{\"name\""));
        assertFalse(result.contains("[\"CodeHeap"));
    }

    @Test
    void formatsJvmLegacyNestedObjectsAsText() throws Exception {
        var result = format("""
                {
                  "type": "jvm",
                  "runtimeInfo": {
                    "vmName": "OpenJDK 64-Bit Server VM",
                    "startTime": 1716962400000
                  },
                  "threadInfo": {
                    "threadCount": 42,
                    "daemonThreadCount": 30
                  }
                }
                """);

        assertTrue(result.contains("Runtime"));
        assertTrue(result.contains("vmName"));
        assertTrue(result.contains("Thread"));
        assertTrue(result.contains("threadCount"));
        assertFalse(result.contains("\"runtimeInfo\""));
    }

    @Test
    void formatsTraceAsNativeTreeText() throws Exception {
        var result = format("""
                {
                  "type": "trace",
                  "jobId": 7,
                  "root": {
                    "timestamp": "2026-05-29 14:31:01.770",
                    "threadName": "XNIO-1 task-25",
                    "threadId": 721,
                    "daemon": false,
                    "priority": 5,
                    "classLoader": "jdk.internal.loader.ClassLoaders$AppClassLoader@1affbebc",
                    "className": "com.riil.insight.appserver.permission.controller.ModelTreeAuthorityController",
                    "methodName": "getTreeNodeDTOS",
                    "cost": 4023156272,
                    "children": [
                      {
                        "className": "com.riil.insight.appserver.permission.service.PermissionMdcService",
                        "methodName": "queryModelTreeDefsWithoutCiCount",
                        "lineNumber": 139,
                        "cost": 4000889582
                      },
                      {
                        "className": "com.riil.insight.appserver.permission.controller.ModelTreeAuthorityController",
                        "methodName": "dealModelTree",
                        "lineNumber": 170,
                        "cost": 2458072
                      },
                      {
                        "className": "com.riil.insight.mdc.model.service.ModelTreeService",
                        "methodName": "recursionTree",
                        "lineNumber": 316,
                        "minCost": 577392,
                        "maxCost": 167463029,
                        "totalCost": 984144323,
                        "times": 21
                      }
                    ]
                  }
                }
                """);

        assertTrue(result.contains("`---ts=2026-05-29 14:31:01.770;thread_name=XNIO-1 task-25;id=721;is_daemon=false;priority=5;TCCL=jdk.internal.loader.ClassLoaders$AppClassLoader@1affbebc"));
        assertTrue(result.contains("    `---[4023.156272ms] com.riil.insight.appserver.permission.controller.ModelTreeAuthorityController:getTreeNodeDTOS()"));
        assertTrue(result.contains("\u001B[31m[99.45% 4000.889582ms]\u001B[0m com.riil.insight.appserver.permission.service.PermissionMdcService:queryModelTreeDefsWithoutCiCount() #139"));
        assertTrue(result.contains("        +---[0.06% 2.458072ms] com.riil.insight.appserver.permission.controller.ModelTreeAuthorityController:dealModelTree() #170"));
        assertTrue(result.contains("        `---[24.46% min=0.577392ms,max=167.463029ms,total=984.144323ms,count=21] com.riil.insight.mdc.model.service.ModelTreeService:recursionTree() #316"));
        assertFalse(result.contains("Trace\n"));
        assertFalse(result.contains("Call Tree"));
        assertFalse(result.contains("cost="));
    }

    @Test
    void skipsSyntheticTraceRootAndKeepsChildPercentages() throws Exception {
        var result = format("""
                {
                  "type": "trace",
                  "jobId": 3,
                  "root": {
                    "timestamp": "2026-05-29 17:17:05.942005",
                    "threadName": "DubboServerHandler-172.17.162.104:30231-thread-201",
                    "threadId": 680,
                    "daemon": true,
                    "priority": 5,
                    "children": [
                      {
                        "className": "com.riil.insight.mdc.model.service.ModelTreeService",
                        "methodName": "getTreeNoCache",
                        "cost": 1157028635,
                        "children": [
                          {
                            "className": "org.springframework.util.CollectionUtils",
                            "methodName": "isEmpty",
                            "lineNumber": 307,
                            "cost": 53311
                          },
                          {
                            "className": "com.riil.insight.mdc.model.service.ModelTreeService",
                            "methodName": "recursionTree",
                            "lineNumber": 316,
                            "minCost": 720013,
                            "maxCost": 231319796,
                            "totalCost": 1136186585,
                            "times": 21
                          }
                        ]
                      }
                    ]
                  }
                }
                """);

        assertFalse(result.contains("[-] -:-()"));
        assertTrue(result.contains("    `---[1157.028635ms] com.riil.insight.mdc.model.service.ModelTreeService:getTreeNoCache()"));
        assertTrue(result.contains("        +---[0.00% 0.053311ms] org.springframework.util.CollectionUtils:isEmpty() #307"));
        assertTrue(result.contains("\u001B[31m[98.20% min=0.720013ms,max=231.319796ms,total=1136.186585ms,count=21]\u001B[0m com.riil.insight.mdc.model.service.ModelTreeService:recursionTree() #316"));
    }

    private String format(String json) throws Exception {
        Method method = ArthasHttpCommandClient.class.getDeclaredMethod("formatResult", JsonNode.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(client, objectMapper.readTree(json), -1);
    }
}
