package org.apache.lucene.index;

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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Random;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.MockTokenizer;
import org.apache.lucene.analysis.MockVariableLengthPayloadFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document2;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FieldTypes;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.MockDirectoryWrapper.Failure;
import org.apache.lucene.store.MockDirectoryWrapper;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.Rethrow;
import org.apache.lucene.util.LuceneTestCase.Nightly;
import org.apache.lucene.util.LuceneTestCase.SuppressCodecs;
import org.apache.lucene.util.TestUtil;

/** 
 * Causes a bunch of fake OOM and checks that no other exceptions are delivered instead,
 * no index corruption is ever created.
 */
@SuppressCodecs("SimpleText")
public class TestIndexWriterOutOfMemory extends LuceneTestCase {
  
  // just one thread, serial merge policy, hopefully debuggable
  private void doTest(MockDirectoryWrapper.Failure failOn) throws Exception {   
    // log all exceptions we hit, in case we fail (for debugging)
    ByteArrayOutputStream exceptionLog = new ByteArrayOutputStream();
    PrintStream exceptionStream = new PrintStream(exceptionLog, true, "UTF-8");
    //PrintStream exceptionStream = System.out;
    
    final long analyzerSeed = random().nextLong();
    final Analyzer analyzer = new Analyzer() {
      @Override
      protected TokenStreamComponents createComponents(String fieldName) {
        MockTokenizer tokenizer = new MockTokenizer(MockTokenizer.WHITESPACE, false);
        tokenizer.setEnableChecks(false); // we are gonna make it angry
        TokenStream stream = tokenizer;
        // emit some payloads
        if (fieldName.contains("payloads")) {
          stream = new MockVariableLengthPayloadFilter(new Random(analyzerSeed), stream);
        }
        return new TokenStreamComponents(tokenizer, stream);
      }
    };
    
    MockDirectoryWrapper dir = null;
    
    final int numIterations = TEST_NIGHTLY ? atLeast(100) : atLeast(5);
    
    STARTOVER:
    for (int iter = 0; iter < numIterations; iter++) {
      try {
        // close from last run
        if (dir != null) {
          dir.close();
        }
        // disable slow things: we don't rely upon sleeps here.
        dir = newMockDirectory();
        dir.setThrottling(MockDirectoryWrapper.Throttling.NEVER);
        dir.setUseSlowOpenClosers(false);
      
        IndexWriterConfig conf = newIndexWriterConfig(analyzer);
        // just for now, try to keep this test reproducible
        conf.setMergeScheduler(new SerialMergeScheduler());
      
        // test never makes it this far...
        int numDocs = atLeast(2000);
      
        IndexWriter iw = new IndexWriter(dir, conf);

        FieldTypes fieldTypes = iw.getFieldTypes();
        fieldTypes.setMultiValued("dv4");
        fieldTypes.setDocValuesType("dv2", DocValuesType.BINARY);
        fieldTypes.setDocValuesType("dv3", DocValuesType.SORTED);
        fieldTypes.enableSorting("dv4");
        fieldTypes.setMultiValued("dv4");
        fieldTypes.setMultiValued("dv5");
        fieldTypes.setMultiValued("stored1");
        fieldTypes.enableTermVectors("text_vectors");

        // nocommit we could just set our analyzer for the payload field?

        iw.commit(); // ensure there is always a commit

        dir.failOn(failOn);
        
        for (int i = 0; i < numDocs; i++) {
          Document2 doc = iw.newDocument();
          doc.addAtom("id", Integer.toString(i));
          doc.addInt("dv", i);
          doc.addBinary("dv2", new BytesRef(Integer.toString(i))); 
          doc.addBinary("dv3", new BytesRef(Integer.toString(i))); 
          doc.addBinary("dv4", new BytesRef(Integer.toString(i)));
          doc.addBinary("dv4", new BytesRef(Integer.toString(i-1)));
          doc.addInt("dv5", i);
          doc.addInt("dv5", i-1);
          doc.addLargeText("text1", TestUtil.randomAnalysisString(random(), 20, true));
          // ensure we store something
          doc.addStored("stored1", "foo");
          doc.addStored("stored1", "bar");
          // ensure we get some payloads (analyzer will insert them for this field):
          doc.addLargeText("text_payloads", TestUtil.randomAnalysisString(random(), 6, true));
          // ensure we get some vectors
          doc.addLargeText("text_vectors", TestUtil.randomAnalysisString(random(), 6, true));
          
          if (random().nextInt(10) > 0) {
            // single doc
            try {
              iw.addDocument(doc);
              // we made it, sometimes delete our doc, or update a dv
              int thingToDo = random().nextInt(4);
              if (thingToDo == 0) {
                iw.deleteDocuments(new Term("id", Integer.toString(i)));
              } else if (thingToDo == 1) {
                iw.updateNumericDocValue(new Term("id", Integer.toString(i)), "dv", i+1L);
              } else if (thingToDo == 2) {
                iw.updateBinaryDocValue(new Term("id", Integer.toString(i)), "dv2", new BytesRef(Integer.toString(i+1)));
              }
            } catch (OutOfMemoryError | AlreadyClosedException disaster) {
              getOOM(disaster, iw, exceptionStream);
              continue STARTOVER;
            }
          } else {
            // block docs
            Document2 doc2 = iw.newDocument();
            doc2.addAtom("id", Integer.toString(-i));
            doc2.addLargeText("text1", TestUtil.randomAnalysisString(random(), 20, true));
            doc2.addStored("stored1", "foo");
            doc2.addStored("stored1", "bar");
            doc2.addLargeText("text_vectors", TestUtil.randomAnalysisString(random(), 6, true));
            
            try {
              iw.addDocuments(Arrays.asList(doc, doc2));
              // we made it, sometimes delete our docs
              if (random().nextBoolean()) {
                iw.deleteDocuments(new Term("id", Integer.toString(i)), new Term("id", Integer.toString(-i)));
              }
            } catch (OutOfMemoryError | AlreadyClosedException disaster) {
              getOOM(disaster, iw, exceptionStream);
              continue STARTOVER;
            }
          }
          
          if (random().nextInt(10) == 0) {
            // trigger flush:
            try {
              if (random().nextBoolean()) {
                DirectoryReader ir = null;
                try {
                  ir = DirectoryReader.open(iw, random().nextBoolean());
                  TestUtil.checkReader(ir);
                } finally {
                  IOUtils.closeWhileHandlingException(ir);
                }
              } else {
                iw.commit();
              }
              if (DirectoryReader.indexExists(dir)) {
                TestUtil.checkIndex(dir);
              }
            } catch (OutOfMemoryError | AlreadyClosedException disaster) {
              getOOM(disaster, iw, exceptionStream);
              continue STARTOVER;
            }
          }
        }
        
        try {
          iw.close();
        } catch (OutOfMemoryError | AlreadyClosedException disaster) {
          getOOM(disaster, iw, exceptionStream);
          continue STARTOVER;
        }
      } catch (Throwable t) {
        System.out.println("Unexpected exception: dumping fake-exception-log:...");
        exceptionStream.flush();
        System.out.println(exceptionLog.toString("UTF-8"));
        System.out.flush();
        Rethrow.rethrow(t);
      }
    }
    dir.close();
    if (VERBOSE) {
      System.out.println("TEST PASSED: dumping fake-exception-log:...");
      System.out.println(exceptionLog.toString("UTF-8"));
    }
  }
  
  private OutOfMemoryError getOOM(Throwable disaster, IndexWriter writer, PrintStream log) {
    Throwable e = disaster;
    if (e instanceof AlreadyClosedException) {
      e = e.getCause();
    }
    
    if (e instanceof OutOfMemoryError && e.getMessage() != null && e.getMessage().startsWith("Fake OutOfMemoryError")) {
      log.println("\nTEST: got expected fake exc:" + e.getMessage());
      e.printStackTrace(log);
      // TODO: remove rollback here, and add this assert to ensure "full OOM protection" anywhere IW does writes
      // assertTrue("hit OOM but writer is still open, WTF: ", writer.isClosed());
      try {
        writer.rollback();
      } catch (Throwable t) {}
      return (OutOfMemoryError) e;
    } else {
      Rethrow.rethrow(disaster);
      return null; // dead
    }
  }

  public void testBasics() throws Exception {
    final Random r = new Random(random().nextLong());
    doTest(new Failure() {
      @Override
      public void eval(MockDirectoryWrapper dir) throws IOException {
        if (r.nextInt(3000) == 0) {
          StackTraceElement stack[] = Thread.currentThread().getStackTrace();
          boolean ok = false;
          for (int i = 0; i < stack.length; i++) {
            if (stack[i].getClassName().equals(IndexWriter.class.getName())) {
              ok = true;
            }
          }
          if (ok) {
            throw new OutOfMemoryError("Fake OutOfMemoryError");
          }
        }
      }
    });
  }
  
  @Nightly
  public void testCheckpoint() throws Exception {
    final Random r = new Random(random().nextLong());
    doTest(new Failure() {
      @Override
      public void eval(MockDirectoryWrapper dir) throws IOException {
        StackTraceElement stack[] = Thread.currentThread().getStackTrace();
        boolean ok = false;
        for (int i = 0; i < stack.length; i++) {
          if (stack[i].getClassName().equals(IndexFileDeleter.class.getName()) && stack[i].getMethodName().equals("checkpoint")) {
            ok = true;
          }
        }
        if (ok && r.nextInt(4) == 0) {
          throw new OutOfMemoryError("Fake OutOfMemoryError");
        }
      }
    });
  }
}
