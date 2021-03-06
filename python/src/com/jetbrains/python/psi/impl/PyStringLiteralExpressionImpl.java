// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.psi.impl;

import com.intellij.icons.AllIcons;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.PsiReferenceService.Hints;
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyElementTypes;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.codeInsight.regexp.PythonVerboseRegexpLanguage;
import com.jetbrains.python.lexer.PythonHighlightingLexer;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import one.util.streamex.StreamEx;
import org.intellij.lang.regexp.DefaultRegExpPropertiesProvider;
import org.intellij.lang.regexp.RegExpLanguageHost;
import org.intellij.lang.regexp.UnicodeCharacterNames;
import org.intellij.lang.regexp.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public class PyStringLiteralExpressionImpl extends PyElementImpl implements PyStringLiteralExpression, RegExpLanguageHost, PsiLiteralValue {

  @Nullable private volatile String myStringValue;
  @Nullable private volatile List<TextRange> myValueTextRanges;
  @Nullable private volatile List<Pair<TextRange, String>> myDecodedFragments;
  private final DefaultRegExpPropertiesProvider myPropertiesProvider;

  public PyStringLiteralExpressionImpl(ASTNode astNode) {
    super(astNode);
    myPropertiesProvider = DefaultRegExpPropertiesProvider.getInstance();
  }

  @Override
  protected void acceptPyVisitor(PyElementVisitor pyVisitor) {
    pyVisitor.visitPyStringLiteralExpression(this);
  }

  @Override
  public void subtreeChanged() {
    super.subtreeChanged();
    myStringValue = null;
    myValueTextRanges = null;
    myDecodedFragments = null;
  }

  @Override
  @NotNull
  public List<TextRange> getStringValueTextRanges() {
    List<TextRange> result = myValueTextRanges;
    if (result == null) {
      final int elementStart = getTextRange().getStartOffset();
      final List<TextRange> ranges = StreamEx.of(getStringElements())
        .map(node -> {
          final int nodeRelativeOffset = node.getTextRange().getStartOffset() - elementStart;
          return node.getContentRange().shiftRight(nodeRelativeOffset);
        })
        .toList();
      myValueTextRanges = result = Collections.unmodifiableList(ranges);
    }
    return result;
  }

  @Override
  @NotNull
  public List<Pair<TextRange, String>> getDecodedFragments() {
    final int elementStart = getTextRange().getStartOffset();
    List<Pair<TextRange, String>> result = myDecodedFragments;
    if (result == null) {
      final List<Pair<TextRange, String>> combined = StreamEx.of(getStringElements())
        .flatMap(node -> StreamEx.of(node.getDecodedFragments())
          .map(pair -> {
            final int nodeRelativeOffset = node.getTextRange().getStartOffset() - elementStart;
            return Pair.create(pair.getFirst().shiftRight(nodeRelativeOffset), pair.getSecond());
          }))
        .toList();
      myDecodedFragments = result = Collections.unmodifiableList(combined);
    }
    return result;
  }

  @Override
  public boolean isDocString() {
    final List<ASTNode> stringNodes = getStringNodes();
    return stringNodes.size() == 1 && stringNodes.get(0).getElementType() == PyTokenTypes.DOCSTRING;
  }

  @Override
  @NotNull
  public List<ASTNode> getStringNodes() {
    final TokenSet stringNodeTypes = TokenSet.orSet(PyTokenTypes.STRING_NODES, TokenSet.create(PyElementTypes.FSTRING_NODE));
    return Arrays.asList(getNode().getChildren(stringNodeTypes));
  }

  @NotNull
  @Override
  public List<PyStringElement> getStringElements() {
    return StreamEx.of(getStringNodes())
      .map(ASTNode::getPsi)
      .select(PyStringElement.class)
      .toList();
  }

  @NotNull
  @Override
  public String getStringValue() {
    //ASTNode child = getNode().getFirstChildNode();
    //assert child != null;
    String result = myStringValue;
    if (result == null) {
      final StringBuilder out = new StringBuilder();
      for (Pair<TextRange, String> fragment : getDecodedFragments()) {
        out.append(fragment.getSecond());
      }
      myStringValue = result = out.toString();
    }
    return result;
  }

  @Nullable
  @Override
  public Object getValue() {
    return getStringValue();
  }

  @Override
  public TextRange getStringValueTextRange() {
    List<TextRange> allRanges = getStringValueTextRanges();
    if (allRanges.size() == 1) {
      return allRanges.get(0);
    }
    if (allRanges.size() > 1) {
      return allRanges.get(0).union(allRanges.get(allRanges.size() - 1));
    }
    return new TextRange(0, getTextLength());
  }

  @Override
  public String toString() {
    return super.toString() + ": " + getStringValue();
  }

  @Override
  public boolean isValidHost() {
    return true;
  }

  @Override
  public PyType getType(@NotNull TypeEvalContext context, @NotNull TypeEvalContext.Key key) {
    final ASTNode firstNode = ContainerUtil.getFirstItem(getStringNodes());
    if (firstNode != null) {
      if (firstNode.getElementType() == PyElementTypes.FSTRING_NODE) {
        // f-strings can't have "b" prefix so they are always unicode 
        return PyBuiltinCache.getInstance(this).getUnicodeType(LanguageLevel.forElement(this));
      }

      PyFile file = PsiTreeUtil.getParentOfType(this, PyFile.class);
      if (file != null) {
        IElementType type = PythonHighlightingLexer.convertStringType(firstNode.getElementType(), 
                                                                      firstNode.getText(),
                                                                      LanguageLevel.forElement(this),
                                                                      file.hasImportFromFuture(FutureFeature.UNICODE_LITERALS));
        if (PyTokenTypes.UNICODE_NODES.contains(type)) {
          return PyBuiltinCache.getInstance(this).getUnicodeType(LanguageLevel.forElement(this));
        }
      }
    }
    return PyBuiltinCache.getInstance(this).getBytesType(LanguageLevel.forElement(this));
  }

  @Override
  @NotNull
  public final PsiReference[] getReferences() {
    return ReferenceProvidersRegistry.getReferencesFromProviders(this, Hints.NO_HINTS);
  }

  @Override
  public PsiReference getReference() {
    return ArrayUtil.getFirstElement(getReferences());
  }

  @Override
  public ItemPresentation getPresentation() {
    return new ItemPresentation() {
      @Nullable
      @Override
      public String getPresentableText() {
        return getStringValue();
      }

      @Nullable
      @Override
      public String getLocationString() {
        String packageForFile = PyElementPresentation.getPackageForFile(getContainingFile());
        return packageForFile != null ? String.format("(%s)", packageForFile) : null;
      }

      @Nullable
      @Override
      public Icon getIcon(boolean unused) {
        return AllIcons.Nodes.Variable;
      }
    };
  }

  @Override
  public PsiLanguageInjectionHost updateText(@NotNull String text) {
    return ElementManipulators.handleContentChange(this, text);
  }

  @Override
  @NotNull
  public LiteralTextEscaper<? extends PsiLanguageInjectionHost> createLiteralTextEscaper() {
    return new StringLiteralTextEscaper(this);
  }

  private static class StringLiteralTextEscaper extends LiteralTextEscaper<PyStringLiteralExpression> {
    private final PyStringLiteralExpressionImpl myHost;

    protected StringLiteralTextEscaper(@NotNull PyStringLiteralExpressionImpl host) {
      super(host);
      myHost = host;
    }

    @Override
    public boolean decode(@NotNull final TextRange rangeInsideHost, @NotNull final StringBuilder outChars) {
      for (Pair<TextRange, String> fragment : myHost.getDecodedFragments()) {
        final TextRange encodedTextRange = fragment.getFirst();
        final TextRange intersection = encodedTextRange.intersection(rangeInsideHost);
        if (intersection != null && !intersection.isEmpty()) {
          final String value = fragment.getSecond();
          final String intersectedValue;
          if (value.codePointCount(0, value.length()) == 1 || value.length() == intersection.getLength()) {
            intersectedValue = value;
          }
          else {
            final int start = Math.max(0, rangeInsideHost.getStartOffset() - encodedTextRange.getStartOffset());
            final int end = Math.min(value.length(), start + intersection.getLength());
            intersectedValue = value.substring(start, end);
          }
          outChars.append(intersectedValue);
        }
      }
      return true;
    }

    @Override
    public int getOffsetInHost(final int offsetInDecoded, @NotNull final TextRange rangeInsideHost) {
      int offset = 0; // running offset in the decoded fragment
      int endOffset = -1;
      for (Pair<TextRange, String> fragment : myHost.getDecodedFragments()) {
        final TextRange encodedTextRange = fragment.getFirst();
        final TextRange intersection = encodedTextRange.intersection(rangeInsideHost);
        if (intersection != null && !intersection.isEmpty()) {
          final String value = fragment.getSecond();
          final int valueLength = value.length();
          final int intersectionLength = intersection.getLength();
          if (valueLength == 0) {
            return -1;
          }
          // A long unicode escape of form \U01234567 can be decoded into a surrogate pair
          else if (value.codePointCount(0, valueLength) == 1) {
            if (offset == offsetInDecoded) {
              return intersection.getStartOffset();
            }
            offset += valueLength;
          }
          else {
            // Literal fragment without escapes: it's safe to use intersection length instead of value length
            if (offset + intersectionLength >= offsetInDecoded) {
              final int delta = offsetInDecoded - offset;
              return intersection.getStartOffset() + delta;
            }
            offset += intersectionLength;
          }
          endOffset = intersection.getEndOffset();
        }
      }
      // XXX: According to the real use of getOffsetInHost() it should return the correct host offset for the offset in decoded at the
      // end of the range inside host, not -1
      if (offset == offsetInDecoded) {
        return endOffset;
      }
      return -1;
    }

    @Override
    public boolean isOneLine() {
      return true;
    }
  }

  @Override
  public int valueOffsetToTextOffset(int valueOffset) {
    return createLiteralTextEscaper().getOffsetInHost(valueOffset, getStringValueTextRange());
  }

  @Override
  public boolean characterNeedsEscaping(char c) {
    if (c == '#') {
      return isVerboseInjection();
    }
    return c == ']' || c == '}' || c == '\"' || c == '\'';
  }

  private boolean isVerboseInjection() {
    List<Pair<PsiElement, TextRange>> files = InjectedLanguageManager.getInstance(getProject()).getInjectedPsiFiles(this);
    if (files != null) {
      for (Pair<PsiElement, TextRange> file : files) {
        Language language = file.getFirst().getLanguage();
        if (language == PythonVerboseRegexpLanguage.INSTANCE) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public boolean supportsPerl5EmbeddedComments() {
    return true;
  }

  @Override
  public boolean supportsPossessiveQuantifiers() {
    return false;
  }

  @Override
  public boolean supportsPythonConditionalRefs() {
    return true;
  }

  @Override
  public boolean supportsNamedGroupSyntax(RegExpGroup group) {
    return group.getType() == RegExpGroup.Type.PYTHON_NAMED_GROUP;
  }

  @Override
  public boolean supportsNamedGroupRefSyntax(RegExpNamedGroupRef ref) {
    return ref.isPythonNamedGroupRef();
  }

  @NotNull
  @Override
  public EnumSet<RegExpGroup.Type> getSupportedNamedGroupTypes(RegExpElement context) {
    return EnumSet.of(RegExpGroup.Type.PYTHON_NAMED_GROUP);
  }

  @Override
  public boolean supportsExtendedHexCharacter(RegExpChar regExpChar) {
    return false;
  }

  @Override
  public Lookbehind supportsLookbehind(@NotNull RegExpGroup lookbehindGroup) {
    return Lookbehind.FIXED_LENGTH_ALTERNATION;
  }

  @Override
  public Long getQuantifierValue(@NotNull RegExpNumber number) {
    try {
      final long result = Long.parseLong(number.getUnescapedText());
      if (result >= 0xFFFFFFFFL /* max unsigned int 32 bits */) return null;
      return result;
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  @Override
  public boolean isValidCategory(@NotNull String category) {
    return myPropertiesProvider.isValidCategory(category);
  }

  @NotNull
  @Override
  public String[][] getAllKnownProperties() {
    return myPropertiesProvider.getAllKnownProperties();
  }

  @Nullable
  @Override
  public String getPropertyDescription(@Nullable String name) {
    return myPropertiesProvider.getPropertyDescription(name);
  }

  @NotNull
  @Override
  public String[][] getKnownCharacterClasses() {
    return myPropertiesProvider.getKnownCharacterClasses();
  }

  @Override
  public boolean supportsNamedCharacters(RegExpNamedCharacter namedCharacter) {
    return LanguageLevel.forElement(this).isAtLeast(LanguageLevel.PYTHON38);
  }

  @Override
  public boolean isValidNamedCharacter(RegExpNamedCharacter namedCharacter) {
    return UnicodeCharacterNames.getCodePoint(namedCharacter.getName()) >= 0;
  }
}
