// Generated from com/rollercoaster/rax/grammar/RaxGrammar.g4 by ANTLR 4.13.2
package com.rollercoaster.rax.grammar;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link RaxGrammarParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface RaxGrammarVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link RaxGrammarParser#raxExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRaxExpression(RaxGrammarParser.RaxExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link RaxGrammarParser#varDeclaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVarDeclaration(RaxGrammarParser.VarDeclarationContext ctx);
	/**
	 * Visit a parse tree produced by the {@code MultDivExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMultDivExpr(RaxGrammarParser.MultDivExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PowerExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPowerExpr(RaxGrammarParser.PowerExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ConcatExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConcatExpr(RaxGrammarParser.ConcatExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ComparisonExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComparisonExpr(RaxGrammarParser.ComparisonExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LetExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLetExpr(RaxGrammarParser.LetExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryExpr(RaxGrammarParser.PrimaryExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code InExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInExpr(RaxGrammarParser.InExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code UnaryExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnaryExpr(RaxGrammarParser.UnaryExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code AddSubExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAddSubExpr(RaxGrammarParser.AddSubExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LogicalExpr}
	 * labeled alternative in {@link RaxGrammarParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLogicalExpr(RaxGrammarParser.LogicalExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LiteralNumber}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLiteralNumber(RaxGrammarParser.LiteralNumberContext ctx);
	/**
	 * Visit a parse tree produced by the {@code LiteralString}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLiteralString(RaxGrammarParser.LiteralStringContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ColumnOrMeasure}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitColumnOrMeasure(RaxGrammarParser.ColumnOrMeasureContext ctx);
	/**
	 * Visit a parse tree produced by the {@code FunctionCall}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionCall(RaxGrammarParser.FunctionCallContext ctx);
	/**
	 * Visit a parse tree produced by the {@code VariableReference}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariableReference(RaxGrammarParser.VariableReferenceContext ctx);
	/**
	 * Visit a parse tree produced by the {@code QuotedTableReference}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQuotedTableReference(RaxGrammarParser.QuotedTableReferenceContext ctx);
	/**
	 * Visit a parse tree produced by the {@code GroupedExpr}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGroupedExpr(RaxGrammarParser.GroupedExprContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TableLiteral}
	 * labeled alternative in {@link RaxGrammarParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableLiteral(RaxGrammarParser.TableLiteralContext ctx);
	/**
	 * Visit a parse tree produced by {@link RaxGrammarParser#tableRef}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableRef(RaxGrammarParser.TableRefContext ctx);
	/**
	 * Visit a parse tree produced by {@link RaxGrammarParser#tableConstructor}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableConstructor(RaxGrammarParser.TableConstructorContext ctx);
	/**
	 * Visit a parse tree produced by {@link RaxGrammarParser#tableRow}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableRow(RaxGrammarParser.TableRowContext ctx);
}