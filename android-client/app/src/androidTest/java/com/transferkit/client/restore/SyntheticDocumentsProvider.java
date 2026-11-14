package com.transferkit.client.restore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Test-only ContentProvider implementing the DocumentsContract calls used by SAF restore. */
public final class SyntheticDocumentsProvider extends ContentProvider {
    private static final String AUTHORITY = "com.transferkit.client.test.synthetic-documents";
    private static final String ROOT_ID = "root";
    private static final String ROOT_DIRECTORY = "transferkit-saf-provider-test";
    private static final String PREFERENCES = "transferkit_saf_provider_test";
    private static final String KEY_RENAME_SUPPORTED = "rename_supported";
    private static final String METHOD_GRANT = "transferkit_test_grant_tree";
    private static final String METHOD_CLEAR = "transferkit_test_clear";
    private static final String METHOD_SET_RENAME = "transferkit_test_set_rename";
    private static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
    private static final String METHOD_RENAME_DOCUMENT = "android:renameDocument";
    private static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";
    private static final String EXTRA_URI = "uri";
    private static final String PATH_TREE = "tree";
    private static final String PATH_DOCUMENT = "document";
    private static final String PATH_CHILDREN = "children";
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    private File root() {
        return new File(getContext().getCacheDir(), ROOT_DIRECTORY);
    }

    @Override
    public boolean onCreate() {
        return root().mkdirs() || root().isDirectory();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        List<String> segments = uri.getPathSegments();
        int childrenIndex = segments.lastIndexOf(PATH_CHILDREN);
        String[] columns = projection == null ? DOCUMENT_COLUMNS : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if (childrenIndex >= 0) {
            if (childrenIndex == 0) throw new IllegalArgumentException("Missing synthetic parent ID");
            String parentId = segments.get(childrenIndex - 1);
            if (!ROOT_ID.equals(parentId)) throw new IllegalArgumentException("Unknown synthetic parent");
            File[] files = root().listFiles();
            if (files != null) {
                Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
                for (File file : files) cursor.addRow(row(documentRow(file.getName()), columns));
            }
            return cursor;
        }
        int documentIndex = segments.lastIndexOf(PATH_DOCUMENT);
        if (documentIndex >= 0) {
            String id = segmentAfter(segments, documentIndex);
            cursor.addRow(row(documentRow(id), columns));
            return cursor;
        }
        throw new IllegalArgumentException("Unsupported synthetic DocumentsContract URI");
    }

    @Override
    public String getType(Uri uri) {
        return ROOT_ID.equals(documentId(uri))
                ? DocumentsContract.Document.MIME_TYPE_DIR
                : "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Use DocumentsContract.createDocument");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        String id = documentId(uri);
        if (ROOT_ID.equals(id)) throw new IllegalArgumentException("Cannot delete synthetic root");
        File file = fileFor(id);
        if (!file.exists()) return 0;
        if (!file.delete()) throw new IllegalStateException("Synthetic delete failed");
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = documentId(uri);
        if (ROOT_ID.equals(id)) throw new FileNotFoundException("Synthetic root is not a file");
        File file = fileFor(id);
        if (!file.exists()) throw new FileNotFoundException("Synthetic document does not exist");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_GRANT.equals(method)) {
            String targetPackage = require(arg, "target package");
            int flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            getContext().grantUriPermission(targetPackage, treeUri(), flags);
            return new Bundle();
        }
        if (METHOD_CLEAR.equals(method)) {
            File[] files = root().listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.delete()) throw new IllegalStateException("Test cleanup failed");
                }
            }
            getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit().clear().commit();
            return new Bundle();
        }
        if (METHOD_SET_RENAME.equals(method)) {
            boolean supported = extras == null || extras.getBoolean(KEY_RENAME_SUPPORTED, true);
            getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_RENAME_SUPPORTED, supported).commit();
            return new Bundle();
        }
        if (METHOD_CREATE_DOCUMENT.equals(method)) {
            Uri parentUri = requireUri(extras, EXTRA_URI);
            if (!parentUri.getPathSegments().contains(PATH_DOCUMENT)) {
                throw new IllegalArgumentException("createDocument requires a parent document URI");
            }
            String parentId = documentId(parentUri);
            String mime = require(extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE), "MIME type");
            String name = require(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME), "display name");
            String createdId = createDocument(parentId, mime, name);
            Bundle result = new Bundle();
            result.putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(treeUri(), createdId));
            return result;
        }
        if (METHOD_RENAME_DOCUMENT.equals(method)) {
            Uri sourceUri = requireUri(extras, EXTRA_URI);
            String id = documentId(sourceUri);
            String name = require(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME), "display name");
            String renamedId = renameDocument(id, name);
            Bundle result = new Bundle();
            result.putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(treeUri(), renamedId));
            return result;
        }
        if (METHOD_DELETE_DOCUMENT.equals(method)) {
            Uri targetUri = requireUri(extras, EXTRA_URI);
            deleteDocument(documentId(targetUri));
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }

    private String createDocument(String parentId, String mimeType, String displayName) {
        if (!ROOT_ID.equals(parentId)) throw new IllegalArgumentException("Unknown synthetic parent");
        validateName(displayName);
        File file = fileFor(displayName);
        if (file.exists()) throw new IllegalStateException("Synthetic document already exists");
        boolean created;
        try {
            created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)
                    ? file.mkdirs() : file.createNewFile();
        } catch (IOException error) {
            throw new IllegalStateException("Could not create synthetic document", error);
        }
        if (!created) throw new IllegalStateException("Could not create synthetic document");
        return displayName;
    }

    private void deleteDocument(String id) {
        if (ROOT_ID.equals(id)) throw new IllegalArgumentException("Cannot delete synthetic root");
        File file = fileFor(id);
        if (file.exists() && !file.delete()) throw new IllegalStateException("Synthetic delete failed");
    }

    private String renameDocument(String id, String displayName) {
        if (!renameSupported()) throw new UnsupportedOperationException("Synthetic rename disabled");
        validateName(displayName);
        File source = fileFor(id);
        File destination = fileFor(displayName);
        if (!source.exists()) throw new IllegalStateException("Synthetic source does not exist");
        if (destination.exists()) throw new IllegalStateException("Refusing to replace an existing synthetic item");
        if (!source.renameTo(destination)) throw new IllegalStateException("Synthetic rename failed");
        return displayName;
    }

    private Object[] documentRow(String id) {
        if (ROOT_ID.equals(id)) {
            return new Object[]{
                    ROOT_ID,
                    "TransferKit synthetic test",
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                            | DocumentsContract.Document.FLAG_SUPPORTS_RENAME,
                    0L,
                    0L,
            };
        }
        File file = fileFor(id);
        int flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        if (renameSupported()) flags |= DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        return new Object[]{
                id,
                file.getName(),
                "application/octet-stream",
                flags,
                file.length(),
                file.lastModified(),
        };
    }

    private String documentId(Uri uri) {
        List<String> segments = uri.getPathSegments();
        int documentIndex = segments.lastIndexOf(PATH_DOCUMENT);
        if (documentIndex >= 0) return segmentAfter(segments, documentIndex);
        int treeIndex = segments.lastIndexOf(PATH_TREE);
        if (treeIndex >= 0) return segmentAfter(segments, treeIndex);
        throw new IllegalArgumentException("Unsupported synthetic document URI");
    }

    private String segmentAfter(List<String> segments, int index) {
        if (index + 1 >= segments.size()) throw new IllegalArgumentException("Missing synthetic document ID");
        return segments.get(index + 1);
    }

    private Uri treeUri() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    }

    private File fileFor(String id) {
        if (ROOT_ID.equals(id)) return root();
        try {
            File canonicalRoot = root().getCanonicalFile();
            File candidate = new File(root(), id).getCanonicalFile();
            if (!candidate.toPath().startsWith(canonicalRoot.toPath()) || candidate.equals(canonicalRoot)) {
                throw new IllegalArgumentException("Invalid synthetic document ID");
            }
            return candidate;
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid synthetic document ID", error);
        }
    }

    private boolean renameSupported() {
        return getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_RENAME_SUPPORTED, true);
    }

    private void validateName(String name) {
        if (name.trim().isEmpty() || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Unsafe synthetic name");
        }
    }

    private Object[] row(Object[] values, String[] columns) {
        Object[] result = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            int index = Arrays.asList(DOCUMENT_COLUMNS).indexOf(columns[i]);
            result[i] = index < 0 ? null : values[index];
        }
        return result;
    }

    private Uri requireUri(Bundle extras, String key) {
        if (extras == null) throw new IllegalArgumentException("Missing SAF extras");
        Uri uri = extras.getParcelable(key);
        if (uri == null) throw new IllegalArgumentException("Missing SAF URI");
        return uri;
    }

    private String require(String value, String label) {
        if (value == null) throw new IllegalArgumentException("Missing " + label);
        return value;
    }
}
