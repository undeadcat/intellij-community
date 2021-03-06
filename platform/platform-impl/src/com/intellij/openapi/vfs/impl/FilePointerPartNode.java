/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vfs.impl;

import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.util.text.StringUtilRt;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem;
import com.intellij.openapi.vfs.newvfs.ManagingFS;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem;
import com.intellij.openapi.vfs.newvfs.impl.FileNameCache;
import com.intellij.openapi.vfs.newvfs.impl.NullVirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Trie data structure for succinct storage and fast retrieval of file pointers.
 * File pointer "a/b/x.txt" is stored in the tree with nodes a->b->x.txt
 */
class FilePointerPartNode {
  private static final FilePointerPartNode[] EMPTY_ARRAY = new FilePointerPartNode[0];
  private final int nameId; // name id of the VirtualFile corresponding to this node
  @NotNull
  FilePointerPartNode[] children = EMPTY_ARRAY; // sorted by this.getName()
  final FilePointerPartNode parent;
  // file pointers for this exact path (e.g. concatenation of all "part" fields down from the root).
  // Either VirtualFilePointerImpl or VirtualFilePointerImpl[] (when it so happened that several pointers merged into one node - e.g. after file rename onto existing pointer)
  private Object leaves;

  // in case there is file pointer exists for this part, its info is saved here
  volatile Pair<VirtualFile, String> myFileAndUrl; // must not be both null
  volatile long myLastUpdated = -1;
  volatile int useCount;

  int pointersUnder;   // number of alive pointers in this node plus all nodes beneath
  private static final VirtualFileManager ourFileManager = VirtualFileManager.getInstance();

  private FilePointerPartNode(int nameId, @NotNull FilePointerPartNode parent) {
    assert nameId > 0 : nameId + "; " + getClass();
    this.nameId = nameId;
    this.parent = parent;
  }

  boolean urlEndsWithName(@NotNull String urlAfter, VirtualFile fileAfter) {
    if (fileAfter != null) {
      return nameId == getNameId(fileAfter);
    }
    return StringUtil.endsWith(urlAfter, getName());
  }

  @NotNull
  static FilePointerPartNode createFakeRoot() {
    return new FilePointerPartNode(null) {
      @Override
      public String toString() {
        return "root -> "+children.length;
      }

      @NotNull
      @Override
      CharSequence getName() {
        return "";
      }
    };
  }

  // for creating fake root
  FilePointerPartNode(FilePointerPartNode parent) {
    nameId = -1;
    this.parent = parent;
  }

  @NotNull
  static CharSequence fromNameId(int nameId) {
    return FileNameCache.getVFileName(nameId);
  }

  @NotNull
  CharSequence getName() {
    return FileNameCache.getVFileName(nameId);
  }

  @Override
  public String toString() {
    return getName() + (children.length == 0 ? "" : " -> "+children.length);
  }

  /**
   * Tries to match the given path ({@code (parent != null ? parent.getPath() : "") + (separator ? "/" : "") + childName.substring(childStart)})
   * with the trie structure of FilePointerPartNodes
   * @return the node (in outNode[0]) and length of matched characters in that node, or -1 if there is no match.
   * <p>Recursive nodes (i.e. the nodes containing VFP with recursive==true) will be added to outDirs.
   * @param parentNameId is equal to {@code parent != null ? parent.getName() : null}
   */
  private FilePointerPartNode matchById(@Nullable VirtualFile parent,
                                        int parentNameId,
                                        int childNameId,
                                        @Nullable List<? super FilePointerPartNode> outDirs,
                                        boolean createIfNotFound) {
    assert childNameId != -1 && (parent == null) == (parentNameId == -1);
    FilePointerPartNode leaf;
    if (parent == null) {
      leaf = this;
    }
    else {
      VirtualFile gParent = parent.getParent();
      int gParentNameId = getNameId(gParent);
      leaf = matchById(gParent, gParentNameId, parentNameId, outDirs, createIfNotFound);
      if (leaf == null) return null;
    }

    leaf.addRecursiveDirectoryPtrTo(outDirs);
    return leaf.findChildByNameId(childNameId, createIfNotFound);
  }

  private static int getNameId(VirtualFile file) {
    return file == null ? -1 : ((VirtualFileSystemEntry)file).getNameId();
  }

  private FilePointerPartNode findByExistingNameId(@Nullable VirtualFile parent,
                                                   int childNameId,
                                                   @Nullable List<? super FilePointerPartNode> outDirs) {
    if (childNameId <= 0) throw new IllegalArgumentException("invalid argument childNameId: "+childNameId);
    FilePointerPartNode leaf;
    if (parent == null) {
      leaf = this;
    }
    else {
      int nameId = getNameId(parent);
      VirtualFile gParent = parent.getParent();
      int gParentNameId = getNameId(gParent);
      leaf = matchById(gParent, gParentNameId, nameId, outDirs, false);
      if (leaf == null) return null;
    }

    leaf.addRecursiveDirectoryPtrTo(outDirs);
    return leaf.findChildByNameId(childNameId, false);
  }


  // returns start index of the name (i.e. path[return..length) is considered a name)
  private static int extractName(@NotNull CharSequence path, int length) {
    if (length == 1 && path.charAt(0) == '/') {
      return 0; // in case of TEMP file system there is this weird ROOT file
    }
    int i = StringUtil.lastIndexOf(path, '/', 0, length);
    return i + 1;
  }

  private FilePointerPartNode findChildByNameId(int nameId, boolean createIfNotFound) {
    if (nameId <= 0) throw new IllegalArgumentException("invalid argument nameId: "+nameId);
    for (FilePointerPartNode child : children) {
      if (child.nameEqualTo(nameId)) return child;
    }
    if (createIfNotFound) {
      CharSequence name = fromNameId(nameId);
      int index = binarySearchChildByName(name);
      FilePointerPartNode child;
      assert index < 0 : index + " : child= '" + (child = children[index]) + "'"
                         + "; child.nameEqualTo(nameId)=" + child.nameEqualTo(nameId)
                         + "; child.getClass()=" + child.getClass()
                         + "; child.nameId=" + child.nameId
                         + "; child.getName()='" + child.getName() + "'"
                         + "; nameId=" + nameId
                         + "; name='" + name + "'"
                         + "; compare(child) = " + StringUtil.compare(child.getName(), name, !SystemInfo.isFileSystemCaseSensitive) + ";"
                         + " UrlPart.nameEquals: " + FileUtil.PATH_CHAR_SEQUENCE_HASHING_STRATEGY.equals(child.getName(), fromNameId(nameId))
                         + "; name.equals(child.getName())=" + name.equals(child.getName())
        ;
      FilePointerPartNode node = new FilePointerPartNode(nameId, this);
      children = ArrayUtil.insert(children, -index-1, node);
      return node;
    }
    return null;
  }

  boolean nameEqualTo(int nameId) {
    return this.nameId == nameId;
  }

  private int binarySearchChildByName(@NotNull CharSequence name) {
    return ObjectUtils.binarySearch(0, children.length, i -> {
      FilePointerPartNode child = children[i];
      CharSequence childName = child.getName();
      return StringUtil.compare(childName, name, !SystemInfo.isFileSystemCaseSensitive);
    });
  }

  private void addRecursiveDirectoryPtrTo(@Nullable List<? super FilePointerPartNode> dirs) {
    if(dirs != null && hasRecursiveDirectoryPointer() && ContainerUtil.getLastItem(dirs) != this) {
      dirs.add(this);
    }
  }

  /**
   * Appends to {@code out} all nodes under this node whose path (beginning from this node) starts with the given path
   * ({@code (parent != null ? parent.getPath() : "") + (separator ? "/" : "") + childName}) and all nodes under this node with recursive directory pointers whose
   * path is ancestor of the given path.
   */
  void addRelevantPointersFrom(@Nullable VirtualFile parent,
                               int childNameId,
                               @NotNull List<? super FilePointerPartNode> out,
                               boolean addSubdirectoryPointers) {
    if (childNameId <= 0) throw new IllegalArgumentException("invalid argument childNameId: "+childNameId);
    FilePointerPartNode node = findByExistingNameId(parent, childNameId, out);
    if (node != null) {
      if (node.leaves != null) {
        out.add(node);
      }
      if (addSubdirectoryPointers) {
        // when "a/b" changed, treat all "a/b/*" virtual file pointers as changed because that's what happens on directory rename "a"->"newA": "a" deleted and "newA" created
        addAllPointersStrictlyUnder(node, out);
      }
    }
  }

  private boolean hasRecursiveDirectoryPointer() {
    if (leaves == null) return false;
    if (leaves instanceof VirtualFilePointer) {
      return ((VirtualFilePointer)leaves).isRecursive();
    }
    VirtualFilePointerImpl[] leaves = (VirtualFilePointerImpl[])this.leaves;
    for (VirtualFilePointerImpl leaf : leaves) {
      if (leaf.isRecursive()) return true;
    }
    return false;
  }

  private static void addAllPointersStrictlyUnder(@NotNull FilePointerPartNode node, @NotNull List<? super FilePointerPartNode> out) {
    for (FilePointerPartNode child : node.children) {
      if (child.leaves != null) {
        out.add(child);
      }
      addAllPointersStrictlyUnder(child, out);
    }
  }

  void checkConsistency() {
    if (VirtualFilePointerManagerImpl.IS_UNDER_UNIT_TEST && !ApplicationInfoImpl.isInStressTest()) {
      doCheckConsistency(false);
    }
  }

  private void doCheckConsistency(boolean dotDotOccurred) {
    CharSequence part = getName();
    int dotDotIndex = StringUtil.indexOf(part, "..");
    if (dotDotIndex != -1) {
      // part must not contain "/.." nor "../" nor be just ".."
      // (except when the pointer was created from URL of non-existing file with ".." inside)
      dotDotOccurred |= part.equals("..") || dotDotIndex != 0 && part.charAt(dotDotIndex - 1) == '/' || dotDotIndex < part.length() - 2 && part.charAt(dotDotIndex + 2) == '/';
    }
    int childSum = 0;
    for (FilePointerPartNode child : children) {
      childSum += child.pointersUnder;
      child.doCheckConsistency(dotDotOccurred);
      assert child.parent == this;
    }
    childSum += leavesNumber();
    assert (useCount == 0) == (leaves == null) : useCount + " - " + (leaves instanceof VirtualFilePointerImpl ? leaves : Arrays.toString((VirtualFilePointerImpl[])leaves));
    assert pointersUnder == childSum : "expected: "+pointersUnder+"; actual: "+childSum;
    Pair<VirtualFile, String> fileAndUrl = myFileAndUrl;
    if (fileAndUrl != null && fileAndUrl.second != null) {
      String url = fileAndUrl.second;
      String path = StringUtil.trimEnd(VfsUtilCore.urlToPath(url), JarFileSystem.JAR_SEPARATOR);
      assert StringUtilRt.equal(path.substring(path.length() - part.length()), part, SystemInfo.isFileSystemCaseSensitive) : "part is: '" + part + "' but url is: '" + url + "'";
      assert StringUtilRt.equal(new File(path).getName(), getName(), SystemInfo.isFileSystemCaseSensitive) : "fileAndUrl: " + fileAndUrl + "; but this: " + this;
    }
    boolean hasFile = fileAndUrl != null && fileAndUrl.first != null;
    if (hasFile) {
      assert fileAndUrl.first.getName().equals(getName().toString()) : "fileAndUrl: "+fileAndUrl +"; but this: "+this;
    }
    // when the node contains real file its path should be canonical
    assert !hasFile || !dotDotOccurred : "Path is not canonical: '" + getUrl() + "'; my part: '" + part + "'";
  }

  // returns root node
  @NotNull
  FilePointerPartNode remove() {
    int pointersNumber = leavesNumber();
    assert leaves != null : toString();
    associate(null, null);
    useCount = 0;
    FilePointerPartNode node;
    for (node = this; node.parent != null; node = node.parent) {
      int pointersAfter = node.pointersUnder-=pointersNumber;
      if (pointersAfter == 0) {
        node.parent.children = ArrayUtil.remove(node.parent.children, node);
      }
    }
    if ((node.pointersUnder-=pointersNumber) == 0) {
      node.children = EMPTY_ARRAY; // clear root node, especially in tests
    }
    return node;
  }

  @Nullable("null means this node's myFileAndUrl became invalid (e.g. after splitting into two other nodes)")
  // returns pair.second != null always
  Pair<VirtualFile, String> update() {
    final long lastUpdated = myLastUpdated;
    final Pair<VirtualFile, String> fileAndUrl = myFileAndUrl;
    if (fileAndUrl == null) return null;
    final long fsModCount = ManagingFS.getInstance().getStructureModificationCount();
    if (lastUpdated == fsModCount) return fileAndUrl;
    VirtualFile file = fileAndUrl.first;
    String url = fileAndUrl.second;
    boolean changed = false;

    if (url == null) {
      url = file.getUrl();
      if (!file.isValid()) file = null;
      changed = true;
    }
    boolean fileIsValid = file != null && file.isValid();
    if (file != null && !fileIsValid) {
      file = null;
      changed = true;
    }
    if (file == null) {
      file = ourFileManager.findFileByUrl(url);
      fileIsValid = file != null && file.isValid();
      if (file != null) {
        changed = true;
      }
    }
    if (file != null) {
      if (fileIsValid) {
        url = file.getUrl(); // refresh url, it can differ
        changed |= !url.equals(fileAndUrl.second);
      }
      else {
        file = null; // can't find, try next time
        changed = true;
      }
    }
    Pair<VirtualFile, String> result;
    if (changed) {
      result = Pair.create(file, url);
      synchronized (VirtualFilePointerManager.getInstance()) {
        Pair<VirtualFile, String> storedFileAndUrl = myFileAndUrl;
        if (storedFileAndUrl == null || storedFileAndUrl != fileAndUrl) return null; // somebody splitted this node in the meantime, try to re-compute
        myFileAndUrl = result;
      }
    }
    else {
      result = fileAndUrl;
    }
    myLastUpdated = fsModCount; // must be the last
    return result;
  }

  void associate(Object leaves, Pair<VirtualFile, String> fileAndUrl) {
    this.leaves = leaves;
    myFileAndUrl = fileAndUrl;
    // assign myNode last because .update() reads that field outside lock
    if (leaves != null) {
      if (leaves instanceof VirtualFilePointerImpl) {
        ((VirtualFilePointerImpl)leaves).myNode = this;
      }
      else {
        for (VirtualFilePointerImpl pointer : (VirtualFilePointerImpl[])leaves) {
          pointer.myNode = this;
        }
      }
    }
    myLastUpdated = -1;
  }

  int incrementUsageCount(int delta) {
    return useCount+=delta;
  }

  int numberOfPointersUnder() {
    return pointersUnder;
  }

  VirtualFilePointerImpl getAnyPointer() {
    Object leaves = this.leaves;
    return leaves == null ? null : leaves instanceof VirtualFilePointerImpl ? (VirtualFilePointerImpl)leaves : ((VirtualFilePointerImpl[])leaves)[0];
  }

  @NotNull
  private String getUrl() {
    return parent == null ? getName().toString() : parent.getUrl() + "/"+getName();
  }

  private int leavesNumber() {
    Object leaves = this.leaves;
    return leaves == null ? 0 : leaves instanceof VirtualFilePointerImpl ? 1 : ((VirtualFilePointerImpl[])leaves).length;
  }

  void addAllPointersTo(@NotNull Collection<? super VirtualFilePointerImpl> outList) {
    Object leaves = this.leaves;
    if (leaves == null) {
      return;
    }
    if (leaves instanceof VirtualFilePointerImpl) {
      outList.add((VirtualFilePointerImpl)leaves);
    }
    else {
      ContainerUtil.addAll(outList, (VirtualFilePointerImpl[])leaves);
    }
  }

  @NotNull
  FilePointerPartNode findOrCreateNodeByFile(@NotNull VirtualFile file) {
    int nameId = getNameId(file);
    VirtualFile parent = file.getParent();
    int parentNameId = getNameId(parent);
    return matchById(parent, parentNameId, nameId, null, true);
  }

  @NotNull
  static FilePointerPartNode findOrCreateNodeByPath(@NotNull FilePointerPartNode rootNode, @NotNull String path, @NotNull NewVirtualFileSystem fs) {
    List<String> names = splitNames(path);
    NewVirtualFile fsRoot = null;

    VirtualFile NEVER_TRIED_TO_FIND = NullVirtualFile.INSTANCE;
    // we try to never call file.findChild() because it's expensive
    VirtualFile currentFile = NEVER_TRIED_TO_FIND;
    FilePointerPartNode currentNode = rootNode;
    for (int i = names.size() - 1; i >= 0; i--) {
      String name = names.get(i);
      int index = currentNode.binarySearchChildByName(name);
      if (index >= 0) {
        currentNode = currentNode.children[index];
        currentFile = currentFile == NEVER_TRIED_TO_FIND || currentFile == null ? currentFile : currentFile.findChild(name);
        continue;
      }
      // create and insert new node
      // first, have to check if the file root/names(end)/.../names[i] exists
      // if yes, create nameId-based FilePinterPartNode (for faster search and memory efficiency),
      // if not, create temp UrlPartNode which will be replaced with FPPN when the real file is created
      if (currentFile == NEVER_TRIED_TO_FIND) {
        if (fsRoot == null) {
          String rootPath = ContainerUtil.getLastItem(names);
          fsRoot = ManagingFS.getInstance().findRoot(rootPath, fs instanceof ArchiveFileSystem ? LocalFileSystem.getInstance() : fs);
        }
        currentFile = fsRoot == null ? null : findFileFromRoot(fsRoot, fs, names, i);
      }
      else {
        currentFile = currentFile == null ? null : currentFile.findChild(name);
      }
      FilePointerPartNode child = currentFile == null ? new UrlPartNode(name, currentNode)
                                                      : new FilePointerPartNode(getNameId(currentFile), currentNode);

      currentNode.children = ArrayUtil.insert(currentNode.children, -index - 1, child);
      currentNode = child;
      if (i != 0 && fs instanceof ArchiveFileSystem && currentFile != null && !currentFile.isDirectory()) {
        currentFile = ((ArchiveFileSystem)fs).getRootByLocal(currentFile);
      }
    }
    return currentNode;
  }

  @NotNull
  private static List<String> splitNames(@NotNull String path) {
    List<String> names = new ArrayList<>(20);
    int end = path.length();
    while (true) {
      int startIndex = extractName(path, end);
      assert startIndex != end : "startIndex: "+startIndex+"; end: "+end+"; path:'"+path+"'; toExtract: '"+path.substring(0, end)+"'";
      names.add(path.substring(startIndex, end));
      if (startIndex == 0) {
        break;
      }
      int skipSeparator = StringUtil.endsWith(path, 0, startIndex, JarFileSystem.JAR_SEPARATOR) ? 2 : 1;
      end = startIndex - skipSeparator;
      if (end == 0 && path.charAt(0) == '/') {
        end = 1; // here's this weird ROOT file in temp system
      }
    }
    return names;
  }

  private static VirtualFile findFileFromRoot(@NotNull NewVirtualFile root,
                                              @NotNull NewVirtualFileSystem fs,
                                              @NotNull List<String> names,
                                              int startIndex) {
    VirtualFile file = root;
    // start from before-the-last because it's the root, which we already found
    for (int i = names.size() - 2; i >= startIndex; i--) {
      String name = names.get(i);
      file = file.findChild(name);
      if (fs instanceof ArchiveFileSystem && file != null && !file.isDirectory() && file.getFileSystem() != fs) {
        file = ((ArchiveFileSystem)fs).getRootByLocal(file);
      }
      if (file == null) break;
    }
    return file;
  }
}
