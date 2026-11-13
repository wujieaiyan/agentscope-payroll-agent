package io.agentscope.examples.payroll.kb;

import java.util.Locale;

/** 知识库模块枚举，字符串值直接落库并返回给前端。 */
public final class KbEnums {

    private KbEnums() {
    }

    public enum IndexStrategy {
        VECTOR, HYBRID, GRAPH;

        public static IndexStrategy parse(String value, IndexStrategy fallback) {
            return enumValue(IndexStrategy.class, value, fallback);
        }
    }

    public enum SeriesStatus {
        DRAFT, ACTIVE, DISABLED, DELETING, DELETED;

        public static SeriesStatus parse(String value, SeriesStatus fallback) {
            return enumValue(SeriesStatus.class, value, fallback);
        }
    }

    public enum DocStatus {
        PENDING, PARSING, CHUNKING, INDEXING, READY, FAILED, DISABLED, DELETING, DELETED;

        public static DocStatus parse(String value, DocStatus fallback) {
            return enumValue(DocStatus.class, value, fallback);
        }
    }

    public enum TaskStatus {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELING, CANCELED;

        public static TaskStatus parse(String value, TaskStatus fallback) {
            return enumValue(TaskStatus.class, value, fallback);
        }
    }

    public enum TaskType {
        INGEST, REBUILD, FULL_REBUILD, DELETE_CLEANUP, REPARSE;

        public static TaskType parse(String value, TaskType fallback) {
            return enumValue(TaskType.class, value, fallback);
        }
    }

    public enum ChunkLevel {
        SINGLE, PARENT, CHILD;

        public static ChunkLevel parse(String value, ChunkLevel fallback) {
            return enumValue(ChunkLevel.class, value, fallback);
        }
    }

    public enum SubjectType {
        USER, DEPT;

        public static SubjectType parse(String value, SubjectType fallback) {
            return enumValue(SubjectType.class, value, fallback);
        }
    }

    public enum KbRole {
        READER(1),
        EDITOR(2),
        ADMIN(3),
        OWNER(4);

        private final int rank;

        KbRole(int rank) {
            this.rank = rank;
        }

        public boolean atLeast(KbRole other) {
            return rank >= other.rank;
        }

        public static KbRole parse(String value, KbRole fallback) {
            return enumValue(KbRole.class, value, fallback);
        }

        public static KbRole max(KbRole left, KbRole right) {
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            return left.atLeast(right) ? left : right;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
