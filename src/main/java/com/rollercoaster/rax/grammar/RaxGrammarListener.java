// Generated from com/rollercoaster/rax/grammar/RaxGrammar.g4 by ANTLR 4.13.2
package com.rollercoaster.rax.grammar;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link RaxGrammarParser}.
 */
public interface RaxGrammarListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link RaxGrammarParser#raxExpression}.
	 * @param ctx the parse tree
	 */
	void enterRaxExpression(RaxGrammarParser.RaxExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link RaxGrammarParser#raxExpression}.
	 * @param ctx the parse tree
	 */
	void exitRaxExpression(RaxGrammarParser.RaxExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link RaxGrammarParser#varDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterVarDeclaration(RaxGrammarParser.VarDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link RaxGrammarParser#varDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitVarDeclaration(RaxGrammarParser.VarDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MultDivExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMultDivExpr(RaxGrammarParser.MultDivExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MultDivExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMultDivExpr(RaxGrammarParser.MultDivExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PowerExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPowerExpr(RaxGrammarParser.PowerExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PowerExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPowerExpr(RaxGrammarParser.PowerExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ConcatExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterConcatExpr(RaxGrammarParser.ConcatExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ConcatExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitConcatExpr(RaxGrammarParser.ConcatExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ComparisonExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterComparisonExpr(RaxGrammarParser.ComparisonExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ComparisonExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitComparisonExpr(RaxGrammarParser.ComparisonExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LetExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLetExpr(RaxGrammarParser.LetExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LetExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLetExpr(RaxGrammarParser.LetExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PrimaryExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPrimaryExpr(RaxGrammarParser.PrimaryExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PrimaryExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPrimaryExpr(RaxGrammarParser.PrimaryExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code InExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterInExpr(RaxGrammarParser.InExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code InExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitInExpr(RaxGrammarParser.InExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code UnaryExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterUnaryExpr(RaxGrammarParser.UnaryExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code UnaryExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitUnaryExpr(RaxGrammarParser.UnaryExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code AddSubExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAddSubExpr(RaxGrammarParser.AddSubExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AddSubExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAddSubExpr(RaxGrammarParser.AddSubExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LogicalExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLogicalExpr(RaxGrammarParser.LogicalExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LogicalExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLogicalExpr(RaxGrammarParser.LogicalExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LiteralNumber}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterLiteralNumber(RaxGrammarParser.LiteralNumberContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LiteralNumber}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitLiteralNumber(RaxGrammarParser.LiteralNumberContext ctx);
	/**
	 * Enter a parse tree produced by the {@code LiteralString}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterLiteralString(RaxGrammarParser.LiteralStringContext ctx);
	/**
	 * Exit a parse tree produced by the {@code LiteralString}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitLiteralString(RaxGrammarParser.LiteralStringContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ColumnOrMeasure}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterColumnOrMeasure(RaxGrammarParser.ColumnOrMeasureContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ColumnOrMeasure}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitColumnOrMeasure(RaxGrammarParser.ColumnOrMeasureContext ctx);
	/**
	 * Enter a parse tree produced by the {@code FunctionCall}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterFunctionCall(RaxGrammarParser.FunctionCallContext ctx);
	/**
	 * Exit a parse tree produced by the {@code FunctionCall}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitFunctionCall(RaxGrammarParser.FunctionCallContext ctx);
	/**
	 * Enter a parse tree produced by the {@code VariableReference}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterVariableReference(RaxGrammarParser.VariableReferenceContext ctx);
	/**
	 * Exit a parse tree produced by the {@code VariableReference}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitVariableReference(RaxGrammarParser.VariableReferenceContext ctx);
	/**
	 * Enter a parse tree produced by the {@code QuotedTableReference}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterQuotedTableReference(RaxGrammarParser.QuotedTableReferenceContext ctx);
	/**
	 * Exit a parse tree produced by the {@code QuotedTableReference}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitQuotedTableReference(RaxGrammarParser.QuotedTableReferenceContext ctx);
	/**
	 * Enter a parse tree produced by the {@code GroupedExpr}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterGroupedExpr(RaxGrammarParser.GroupedExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code GroupedExpr}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitGroupedExpr(RaxGrammarParser.GroupedExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TableLiteral}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterTableLiteral(RaxGrammarParser.TableLiteralContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TableLiteral}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitTableLiteral(RaxGrammarParser.TableLiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link RaxGrammarParser#tableRef}.
	 * @param ctx the parse tree
	 */
	void enterTableRef(RaxGrammarParser.TableRefContext ctx);
	/**
	 * Exit a parse tree produced by {@link RaxGrammarParser#tableRef}.
	 * @param ctx the parse tree
	 */
	void exitTableRef(RaxGrammarParser.TableRefContext ctx);
	/**
	 * Enter a parse tree produced by {@link RaxGrammarParser#tableConstructor}.
	 * @param ctx the parse tree
	 */
	void enterTableConstructor(RaxGrammarParser.TableConstructorContext ctx);
	/**
	 * Exit a parse tree produced by {@link RaxGrammarParser#tableConstructor}.
	 * @param ctx the parse tree
	 */
	void exitTableConstructor(RaxGrammarParser.TableConstructorContext ctx);
	/**
	 * Enter a parse tree produced by {@link RaxGrammarParser#tableRow}.
	 * @param ctx the parse tree
	 */
	void enterTableRow(RaxGrammarParser.TableRowContext ctx);
	/**
	 * Exit a parse tree produced by {@link RaxGrammarParser#tableRow}.
	 * @param ctx the parse tree
	 */
	void exitTableRow(RaxGrammarParser.TableRowContext ctx);
}