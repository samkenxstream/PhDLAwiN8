/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.gcp.storage;

import com.google.cloud.ReadChannel;
import com.google.cloud.RestorableState;
import com.google.cloud.storage.Acl;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.apache.nifi.processors.gcp.storage.StorageAttributes.BUCKET_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.CACHE_CONTROL_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.COMPONENT_COUNT_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.CONTENT_DISPOSITION_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.CONTENT_ENCODING_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.CONTENT_LANGUAGE_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.CRC32C_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.CREATE_TIME_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.ENCRYPTION_ALGORITHM_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.ENCRYPTION_SHA256_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.ETAG_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.GENERATED_ID_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.GENERATION_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.KEY_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.MD5_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.MEDIA_LINK_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.METAGENERATION_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.OWNER_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.OWNER_TYPE_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.UPDATE_TIME_ATTR;
import static org.apache.nifi.processors.gcp.storage.StorageAttributes.URI_ATTR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FetchGCSObject}.
 */
@ExtendWith(MockitoExtension.class)
public class FetchGCSObjectTest extends AbstractGCSTest {
    private final static String KEY = "test-key";
    private final static Long GENERATION = 5L;
    private static final String CONTENT = "test-content";

    private static final Long SIZE = 100L;
    private static final String CACHE_CONTROL = "test-cache-control";
    private static final Integer COMPONENT_COUNT = 3;
    private static final String CONTENT_ENCODING = "test-content-encoding";
    private static final String CONTENT_LANGUAGE = "test-content-language";
    private static final String CONTENT_TYPE = "test-content-type";
    private static final String CRC32C = "test-crc32c";
    private static final String ENCRYPTION = "test-encryption";
    private static final String ENCRYPTION_SHA256 = "12345678";
    private static final String ETAG = "test-etag";
    private static final String GENERATED_ID = "test-generated-id";
    private static final String MD5 = "test-md5";
    private static final String MEDIA_LINK = "test-media-link";
    private static final Long METAGENERATION = 42L;
    private static final String OWNER_USER_EMAIL = "test-owner-user-email";
    private static final String OWNER_GROUP_EMAIL = "test-owner-group-email";
    private static final String OWNER_DOMAIN = "test-owner-domain";
    private static final String OWNER_PROJECT_ID = "test-owner-project-id";
    private static final String URI = "test-uri";
    private static final String STORAGE_API_URL = "https://localhost";
    private static final String CONTENT_DISPOSITION = "attachment; filename=\"test-content-disposition.txt\"";
    private static final OffsetDateTime CREATE_TIME = OffsetDateTime.of(2023, 1, 1, 0, 0, 23, 0, ZoneOffset.UTC);
    private static final OffsetDateTime UPDATE_TIME = OffsetDateTime.of(2023, 1, 1, 0, 0, 45, 0, ZoneOffset.UTC);

    @Mock
    StorageOptions storageOptions;

    @Mock
    Storage storage;
    private AutoCloseable mockCloseable;

    @BeforeEach
    public void setup() throws Exception {
        mockCloseable = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    public void cleanup() throws Exception {
        final AutoCloseable closeable = mockCloseable;
        mockCloseable = null;
        if (closeable != null) {
            closeable.close();
        }
    }


    @Override
    public AbstractGCSProcessor getProcessor() {
        return new FetchGCSObject() {
            @Override
            protected Storage getCloudService() {
                return storage;
            }

            @Override
            protected Storage getCloudService(final ProcessContext context) {
                return storage;
            }
        };
    }

    private static class MockReadChannel implements ReadChannel {
        private final byte[] toRead;
        private int position = 0;
        private boolean finished;
        private boolean isOpen;

        private MockReadChannel(String textToRead) {
            this.toRead = textToRead.getBytes();
            this.isOpen = true;
            this.finished = false;
        }

        @Override
        public void close() {
            this.isOpen = false;
        }

        @Override
        public void seek(long l) throws IOException {

        }

        @Override
        public void setChunkSize(int i) {

        }

        @Override
        public RestorableState<ReadChannel> capture() {
            return null;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (this.finished) {
                return -1;
            } else {
                if (dst.remaining() > this.toRead.length) {
                    this.finished = true;
                }
                int toWrite = Math.min(this.toRead.length - position, dst.remaining());

                dst.put(this.toRead, this.position, toWrite);
                this.position += toWrite;

                return toWrite;
            }
        }

        @Override
        public boolean isOpen() {
            return this.isOpen;
        }
    }

    @Override
    protected void addRequiredPropertiesToRunner(TestRunner runner) {
        runner.setProperty(FetchGCSObject.BUCKET, BUCKET);
        runner.setProperty(FetchGCSObject.KEY, String.valueOf(KEY));
    }

    @Test
    public void testSuccessfulFetch() throws Exception {
        reset(storageOptions, storage);
        when(storageOptions.getHost()).thenReturn(STORAGE_API_URL);
        when(storage.getOptions()).thenReturn(storageOptions);
        final TestRunner runner = buildNewRunner(getProcessor());
        addRequiredPropertiesToRunner(runner);
        runner.assertValid();

        final Blob blob = mock(Blob.class);

        when(blob.getBucket()).thenReturn(BUCKET);
        when(blob.getName()).thenReturn(KEY);
        when(blob.getSize()).thenReturn(SIZE);
        when(blob.getCacheControl()).thenReturn(CACHE_CONTROL);
        when(blob.getComponentCount()).thenReturn(COMPONENT_COUNT);
        when(blob.getContentEncoding()).thenReturn(CONTENT_ENCODING);
        when(blob.getContentLanguage()).thenReturn(CONTENT_LANGUAGE);
        when(blob.getContentType()).thenReturn(CONTENT_TYPE);
        when(blob.getCrc32c()).thenReturn(CRC32C);

        final BlobInfo.CustomerEncryption mockEncryption = mock(BlobInfo.CustomerEncryption.class);
        when(mockEncryption.getEncryptionAlgorithm()).thenReturn(ENCRYPTION);
        when(mockEncryption.getKeySha256()).thenReturn(ENCRYPTION_SHA256);
        when(blob.getCustomerEncryption()).thenReturn(mockEncryption);

        when(blob.getEtag()).thenReturn(ETAG);
        when(blob.getGeneratedId()).thenReturn(GENERATED_ID);
        when(blob.getGeneration()).thenReturn(GENERATION);
        when(blob.getMd5()).thenReturn(MD5);
        when(blob.getMediaLink()).thenReturn(MEDIA_LINK);
        when(blob.getMetageneration()).thenReturn(METAGENERATION);
        when(blob.getSelfLink()).thenReturn(URI);
        when(blob.getContentDisposition()).thenReturn(CONTENT_DISPOSITION);
        when(blob.getCreateTimeOffsetDateTime()).thenReturn(CREATE_TIME);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(UPDATE_TIME);
        final BlobId blobId = mock(BlobId.class);
        when(blob.getBlobId()).thenReturn(blobId);

        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(storage.reader(any(BlobId.class), any(Storage.BlobSourceOption[].class))).thenReturn(new MockReadChannel(CONTENT));


        runner.enqueue("");

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchGCSObject.REL_SUCCESS);
        runner.assertTransferCount(FetchGCSObject.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(FetchGCSObject.REL_SUCCESS).get(0);

        flowFile.assertContentEquals(CONTENT);
        flowFile.assertAttributeEquals(BUCKET_ATTR, BUCKET);
        flowFile.assertAttributeEquals(KEY_ATTR, KEY);
        flowFile.assertAttributeEquals(CACHE_CONTROL_ATTR, CACHE_CONTROL);
        flowFile.assertAttributeEquals(COMPONENT_COUNT_ATTR, Integer.toString(COMPONENT_COUNT));
        flowFile.assertAttributeEquals(CONTENT_ENCODING_ATTR, CONTENT_ENCODING);
        flowFile.assertAttributeEquals(CONTENT_LANGUAGE_ATTR, CONTENT_LANGUAGE);
        flowFile.assertAttributeEquals(CoreAttributes.MIME_TYPE.key(), CONTENT_TYPE);
        flowFile.assertAttributeEquals(CRC32C_ATTR, CRC32C);
        flowFile.assertAttributeEquals(ENCRYPTION_ALGORITHM_ATTR, ENCRYPTION);
        flowFile.assertAttributeEquals(ENCRYPTION_SHA256_ATTR, ENCRYPTION_SHA256);
        flowFile.assertAttributeEquals(ETAG_ATTR, ETAG);
        flowFile.assertAttributeEquals(GENERATED_ID_ATTR, GENERATED_ID);
        flowFile.assertAttributeEquals(GENERATION_ATTR, Long.toString(GENERATION));
        flowFile.assertAttributeEquals(MD5_ATTR, MD5);
        flowFile.assertAttributeEquals(MEDIA_LINK_ATTR, MEDIA_LINK);
        flowFile.assertAttributeEquals(METAGENERATION_ATTR, Long.toString(METAGENERATION));
        flowFile.assertAttributeEquals(URI_ATTR, URI);
        flowFile.assertAttributeEquals(CONTENT_DISPOSITION_ATTR, CONTENT_DISPOSITION);
        flowFile.assertAttributeEquals(CREATE_TIME_ATTR, Long.toString(CREATE_TIME.toInstant().toEpochMilli()));
        flowFile.assertAttributeEquals(UPDATE_TIME_ATTR, Long.toString(UPDATE_TIME.toInstant().toEpochMilli()));
    }

    @Test
    public void testAclOwnerUser() throws Exception {
        reset(storageOptions, storage);
        when(storageOptions.getHost()).thenReturn(STORAGE_API_URL);
        when(storage.getOptions()).thenReturn(storageOptions);
        final TestRunner runner = buildNewRunner(getProcessor());
        addRequiredPropertiesToRunner(runner);
        runner.assertValid();

        final Blob blob = mock(Blob.class);

        final Acl.User mockUser = mock(Acl.User.class);
        when(mockUser.getEmail()).thenReturn(OWNER_USER_EMAIL);
        when(blob.getOwner()).thenReturn(mockUser);
        final BlobId blobId = mock(BlobId.class);
        when(blob.getBlobId()).thenReturn(blobId);
        when(blob.getCreateTimeOffsetDateTime()).thenReturn(CREATE_TIME);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(UPDATE_TIME);

        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(storage.reader(any(BlobId.class), any(Storage.BlobSourceOption[].class))).thenReturn(new MockReadChannel(CONTENT));

        runner.enqueue("");

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchGCSObject.REL_SUCCESS);
        runner.assertTransferCount(FetchGCSObject.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(FetchGCSObject.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals(OWNER_ATTR, OWNER_USER_EMAIL);
        flowFile.assertAttributeEquals(OWNER_TYPE_ATTR, "user");
    }

    @Test
    public void testAclOwnerGroup() throws Exception {
        reset(storageOptions, storage);
        when(storageOptions.getHost()).thenReturn(STORAGE_API_URL);
        when(storage.getOptions()).thenReturn(storageOptions);
        final TestRunner runner = buildNewRunner(getProcessor());
        addRequiredPropertiesToRunner(runner);
        runner.assertValid();

        final Blob blob = mock(Blob.class);

        final Acl.Group mockGroup = mock(Acl.Group.class);
        when(mockGroup.getEmail()).thenReturn(OWNER_GROUP_EMAIL);
        when(blob.getOwner()).thenReturn(mockGroup);
        final BlobId blobId = mock(BlobId.class);
        when(blob.getBlobId()).thenReturn(blobId);
        when(blob.getCreateTimeOffsetDateTime()).thenReturn(CREATE_TIME);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(UPDATE_TIME);

        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(storage.reader(any(BlobId.class), any(Storage.BlobSourceOption[].class))).thenReturn(new MockReadChannel(CONTENT));

        runner.enqueue("");
        runner.run();

        runner.assertAllFlowFilesTransferred(FetchGCSObject.REL_SUCCESS);
        runner.assertTransferCount(FetchGCSObject.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(FetchGCSObject.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals(OWNER_ATTR, OWNER_GROUP_EMAIL);
        flowFile.assertAttributeEquals(OWNER_TYPE_ATTR, "group");
    }


    @Test
    public void testAclOwnerDomain() throws Exception {
        reset(storageOptions, storage);
        when(storageOptions.getHost()).thenReturn(STORAGE_API_URL);
        when(storage.getOptions()).thenReturn(storageOptions);
        final TestRunner runner = buildNewRunner(getProcessor());
        addRequiredPropertiesToRunner(runner);
        runner.assertValid();

        final Blob blob = mock(Blob.class);

        final Acl.Domain mockDomain = mock(Acl.Domain.class);
        when(mockDomain.getDomain()).thenReturn(OWNER_DOMAIN);
        when(blob.getOwner()).thenReturn(mockDomain);
        final BlobId blobId = mock(BlobId.class);
        when(blob.getBlobId()).thenReturn(blobId);
        when(blob.getCreateTimeOffsetDateTime()).thenReturn(CREATE_TIME);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(UPDATE_TIME);

        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(storage.reader(any(BlobId.class), any(Storage.BlobSourceOption[].class))).thenReturn(new MockReadChannel(CONTENT));

        runner.enqueue("");

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchGCSObject.REL_SUCCESS);
        runner.assertTransferCount(FetchGCSObject.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(FetchGCSObject.REL_SUCCESS).get(0);

        flowFile.assertAttributeEquals(OWNER_ATTR, OWNER_DOMAIN);
        flowFile.assertAttributeEquals(OWNER_TYPE_ATTR, "domain");
    }


    @Test
    public void testAclOwnerProject() throws Exception {
        reset(storageOptions, storage);
        when(storageOptions.getHost()).thenReturn(STORAGE_API_URL);
        when(storage.getOptions()).thenReturn(storageOptions);
        final TestRunner runner = buildNewRunner(getProcessor());
        addRequiredPropertiesToRunner(runner);
        runner.assertValid();

        final Blob blob = mock(Blob.class);
        final Acl.Project mockProject = mock(Acl.Project.class);
        final BlobId blobId = mock(BlobId.class);
        when(mockProject.getProjectId()).thenReturn(OWNER_PROJECT_ID);
        when(blob.getOwner()).thenReturn(mockProject);
        when(blob.getBlobId()).thenReturn(blobId);
        when(blob.getCreateTimeOffsetDateTime()).thenReturn(CREATE_TIME);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(UPDATE_TIME);

        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(storage.reader(any(BlobId.class), any(Storage.BlobSourceOption[].class))).thenReturn(new MockReadChannel(CONTENT));

        runner.enqueue("");

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchGCSObject.REL_SUCCESS);
        runner.assertTransferCount(FetchGCSObject.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(FetchGCSObject.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals(OWNER_ATTR, OWNER_PROJECT_ID);
        flowFile.assertAttributeEquals(OWNER_TYPE_ATTR, "project");
    }

    @Test
    public void testBlobIdWithGeneration() throws Exception {
        reset(storageOptions, storage);
        when(storageOptions.getHost()).thenReturn(STORAGE_API_URL);
        when(storage.getOptions()).thenReturn(storageOptions);
        final TestRunner runner = buildNewRunner(getProcessor());
        addRequiredPropertiesToRunner(runner);

        runner.removeProperty(FetchGCSObject.KEY);
        runner.removeProperty(FetchGCSObject.BUCKET);

        runner.setProperty(FetchGCSObject.GENERATION, String.valueOf(GENERATION));
        runner.assertValid();

        final Blob blob = mock(Blob.class);
        final BlobId blobId = mock(BlobId.class);
        when(blob.getBlobId()).thenReturn(blobId);
        when(blob.getCreateTimeOffsetDateTime()).thenReturn(CREATE_TIME);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(UPDATE_TIME);

        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(storage.reader(any(BlobId.class), any(Storage.BlobSourceOption.class))).thenReturn(new MockReadChannel(CONTENT));

        final Map<String, String> attributes = new HashMap<>();
        attributes.put(BUCKET_ATTR, BUCKET);
        attributes.put(CoreAttributes.FILENAME.key(), KEY);
        runner.enqueue("", attributes);

        runner.run();

        ArgumentCaptor<BlobId> blobIdArgumentCaptor = ArgumentCaptor.forClass(BlobId.class);
        ArgumentCaptor<Storage.BlobSourceOption> blobSourceOptionArgumentCaptor = ArgumentCaptor.forClass(Storage.BlobSourceOption.class);
        verify(storage).get(blobIdArgumentCaptor.capture());
        verify(storage).reader(any(BlobId.class), blobSourceOptionArgumentCaptor.capture());

        final BlobId capturedBlobId = blobIdArgumentCaptor.getValue();

        assertEquals(BUCKET, capturedBlobId.getBucket());
        assertEquals(KEY, capturedBlobId.getName());
        assertEquals(GENERATION, capturedBlobId.getGeneration());


        final Set<Storage.BlobSourceOption> blobSourceOptions = new LinkedHashSet<>(blobSourceOptionArgumentCaptor.getAllValues());
        assertTrue(blobSourceOptions.contains(Storage.BlobSourceOption.generationMatch()));
        assertEquals(1, blobSourceOptions.size());
    }


    @Test
    public void testBlobIdWithEncryption() throws Exception {
        reset(storageOptions, storage);
        when(storageOptions.getHost()).thenReturn(STORAGE_API_URL);
        when(storage.getOptions()).thenReturn(storageOptions);
        final TestRunner runner = buildNewRunner(getProcessor());

        runner.setProperty(FetchGCSObject.ENCRYPTION_KEY, ENCRYPTION_SHA256);
        addRequiredPropertiesToRunner(runner);
        runner.assertValid();

        final Blob blob = mock(Blob.class);
        final BlobId blobId = mock(BlobId.class);
        when(blob.getBlobId()).thenReturn(blobId);
        when(blob.getCreateTimeOffsetDateTime()).thenReturn(CREATE_TIME);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(UPDATE_TIME);

        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(storage.reader(any(BlobId.class), any(Storage.BlobSourceOption.class))).thenReturn(new MockReadChannel(CONTENT));

        runner.enqueue("");

        runner.run();

        ArgumentCaptor<BlobId> blobIdArgumentCaptor = ArgumentCaptor.forClass(BlobId.class);
        ArgumentCaptor<Storage.BlobSourceOption> blobSourceOptionArgumentCaptor = ArgumentCaptor.forClass(Storage.BlobSourceOption.class);
        verify(storage).get(blobIdArgumentCaptor.capture());
        verify(storage).reader(any(BlobId.class), blobSourceOptionArgumentCaptor.capture());

        final BlobId capturedBlobId = blobIdArgumentCaptor.getValue();

        assertEquals(BUCKET, capturedBlobId.getBucket());
        assertEquals(KEY, capturedBlobId.getName());

        assertNull(capturedBlobId.getGeneration());

        final Set<Storage.BlobSourceOption> blobSourceOptions = new LinkedHashSet<>(blobSourceOptionArgumentCaptor.getAllValues());

        assertTrue(blobSourceOptions.contains(Storage.BlobSourceOption.decryptionKey(ENCRYPTION_SHA256)));
        assertEquals(1, blobSourceOptions.size());
    }

    @Test
    public void testStorageExceptionOnFetch() throws Exception {
        reset(storage);
        final TestRunner runner = buildNewRunner(getProcessor());
        addRequiredPropertiesToRunner(runner);
        runner.assertValid();

        when(storage.get(any(BlobId.class))).thenThrow(new StorageException(400, "test-exception"));

        runner.enqueue("");

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchGCSObject.REL_FAILURE);
        runner.assertTransferCount(FetchGCSObject.REL_FAILURE, 1);
    }
}
