/*******************************************************************************
 * Copyright (c) 2024 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.operations.foldingRange;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.CustomFoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.internal.CompletableFutures;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;

import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.isDoneNormally;

/**
 * LSP folding range builder.
 */
public class LSPFoldingRangeBuilder extends CustomFoldingBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(LSPFoldingRangeBuilder.class);

    @Override
    protected void buildLanguageFoldRegions(@NotNull List<FoldingDescriptor> descriptors,
                                            @NotNull PsiElement root,
                                            @NotNull Document document, boolean quick) {
        // if quick flag is set, we do nothing here
        if (quick) {
            return;
        }
        PsiFile file = root.getContainingFile();
        if (file == null) {
            return;
        }

        // Consume textDocument/foldingRanges
        LSPFoldingRangeSupport foldingRangeSupport = LSPFileSupport.getSupport(file).getFoldingRangeSupport();
        CompletableFuture<List<FoldingRange>> foldingRangesFuture = foldingRangeSupport.getFoldingRanges();
        try {
            CompletableFutures.waitUntilDone(foldingRangesFuture);
        } catch (ProcessCanceledException | CancellationException e) {
            // cancel the LSP requests textDocument/foldingRanges
            foldingRangeSupport.cancel();
        } catch (ExecutionException e) {
            LOGGER.error("Error while consuming LSP textDocument/foldingRanges requests", e);
            return;
        }

        if (isDoneNormally(foldingRangesFuture)) {
            // textDocument/foldingRanges has been collected correctly, create list of IJ FoldingDescriptor from LSP FoldingRange list
            List<FoldingRange> foldingRanges = foldingRangesFuture.getNow(null);
            if (foldingRanges != null) {
                for (FoldingRange foldingRange : foldingRanges) {
                    int start = getStartOffset(foldingRange, document);
                    int end = getEndOffset(foldingRange, document);
                    if (start >= end) {
                        // same line, ignore the folding
                        continue;
                    }
                    String collapsedText = foldingRange.getCollapsedText();
                    if (collapsedText != null) {
                        descriptors.add(new FoldingDescriptor(root.getNode(), new TextRange(start, end), null, collapsedText));
                    } else {
                        descriptors.add(new FoldingDescriptor(root.getNode(), new TextRange(start, end)));
                    }
                }
            }
        }
    }


    private static int getStartOffset(@NotNull FoldingRange foldingRange, @NotNull Document document) {
        if (foldingRange.getStartCharacter() == null) {
            return document.getLineEndOffset(foldingRange.getStartLine());
        }
        return LSPIJUtils.toOffset(new Position(foldingRange.getStartLine(), foldingRange.getStartCharacter()), document);
    }

    private static int getEndOffset(@NotNull FoldingRange foldingRange, @NotNull Document document) {
        if (foldingRange.getEndCharacter() == null) {
            return document.getLineEndOffset(foldingRange.getEndLine());
        }
        return LSPIJUtils.toOffset(new Position(foldingRange.getEndLine(), foldingRange.getEndCharacter()), document);
    }

    @Override
    protected String getLanguagePlaceholderText(@NotNull ASTNode node, @NotNull TextRange range) {
        return null;
    }

    @Override
    protected boolean isRegionCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }
}
