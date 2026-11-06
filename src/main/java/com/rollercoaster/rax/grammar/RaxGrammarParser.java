// Generated from com/rollercoaster/rax/grammar/RaxGrammar.g4 by ANTLR 4.13.2
package com.rollercoaster.rax.grammar;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class RaxGrammarParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		VAR=1, RETURN=2, IN=3, NOT=4, LPAREN=5, RPAREN=6, LBRACE=7, RBRACE=8, 
		ARG_SEP=9, PLUS=10, MINUS=11, STAR=12, SLASH=13, CARET=14, CONCAT=15, 
		EQ_STRICT=16, EQ=17, NEQ=18, LT=19, LTE=20, GT=21, GTE=22, AND=23, OR=24, 
		COLUMN_REF=25, QUOTED_TABLE=26, ID=27, NUMBER=28, STRING_LIT=29, SL_COMMENT=30, 
		ML_COMMENT=31, WS=32;
	public static final int
		RULE_raxExpression = 0, RULE_varDeclaration = 1, RULE_expression = 2, 
		RULE_primary = 3, RULE_tableRef = 4, RULE_tableConstructor = 5, RULE_tableRow = 6;
	private static String[] makeRuleNames() {
		return new String[] {
			"raxExpression", "varDeclaration", "expression", "primary", "tableRef", 
			"tableConstructor", "tableRow"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'VAR'", "'RETURN'", "'IN'", "'NOT'", "'('", "')'", "'{'", "'}'", 
			null, "'+'", "'-'", "'*'", "'/'", "'^'", "'&'", "'=='", "'='", null, 
			"'<'", "'<='", "'>'", "'>='", "'&&'", "'||'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "VAR", "RETURN", "IN", "NOT", "LPAREN", "RPAREN", "LBRACE", "RBRACE", 
			"ARG_SEP", "PLUS", "MINUS", "STAR", "SLASH", "CARET", "CONCAT", "EQ_STRICT", 
			"EQ", "NEQ", "LT", "LTE", "GT", "GTE", "AND", "OR", "COLUMN_REF", "QUOTED_TABLE", 
			"ID", "NUMBER", "STRING_LIT", "SL_COMMENT", "ML_COMMENT", "WS"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}

	@Override
	public String getGrammarFileName() { return "RaxGrammar.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public RaxGrammarParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@SuppressWarnings("CheckReturnValue")
	public static class RaxExpressionContext extends ParserRuleContext {
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode EOF() { return getToken(RaxGrammarParser.EOF, 0); }
		public RaxExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_raxExpression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterRaxExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitRaxExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitRaxExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final RaxExpressionContext raxExpression() throws RecognitionException {
		RaxExpressionContext _localctx = new RaxExpressionContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_raxExpression);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(14);
			expression(0);
			setState(15);
			match(EOF);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class VarDeclarationContext extends ParserRuleContext {
		public TerminalNode VAR() { return getToken(RaxGrammarParser.VAR, 0); }
		public TerminalNode ID() { return getToken(RaxGrammarParser.ID, 0); }
		public TerminalNode EQ() { return getToken(RaxGrammarParser.EQ, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public VarDeclarationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_varDeclaration; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterVarDeclaration(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitVarDeclaration(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitVarDeclaration(this);
			else return visitor.visitChildren(this);
		}
	}

	public final VarDeclarationContext varDeclaration() throws RecognitionException {
		VarDeclarationContext _localctx = new VarDeclarationContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_varDeclaration);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(17);
			match(VAR);
			setState(18);
			match(ID);
			setState(19);
			match(EQ);
			setState(20);
			expression(0);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ExpressionContext extends ParserRuleContext {
		public ExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_expression; }
	 
		public ExpressionContext() { }
		public void copyFrom(ExpressionContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class MultDivExprContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode STAR() { return getToken(RaxGrammarParser.STAR, 0); }
		public TerminalNode SLASH() { return getToken(RaxGrammarParser.SLASH, 0); }
		public MultDivExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterMultDivExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitMultDivExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitMultDivExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class PowerExprContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode CARET() { return getToken(RaxGrammarParser.CARET, 0); }
		public PowerExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterPowerExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitPowerExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitPowerExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ConcatExprContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode CONCAT() { return getToken(RaxGrammarParser.CONCAT, 0); }
		public ConcatExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterConcatExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitConcatExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitConcatExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ComparisonExprContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode EQ() { return getToken(RaxGrammarParser.EQ, 0); }
		public TerminalNode EQ_STRICT() { return getToken(RaxGrammarParser.EQ_STRICT, 0); }
		public TerminalNode NEQ() { return getToken(RaxGrammarParser.NEQ, 0); }
		public TerminalNode LT() { return getToken(RaxGrammarParser.LT, 0); }
		public TerminalNode LTE() { return getToken(RaxGrammarParser.LTE, 0); }
		public TerminalNode GT() { return getToken(RaxGrammarParser.GT, 0); }
		public TerminalNode GTE() { return getToken(RaxGrammarParser.GTE, 0); }
		public ComparisonExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterComparisonExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitComparisonExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitComparisonExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LetExprContext extends ExpressionContext {
		public TerminalNode RETURN() { return getToken(RaxGrammarParser.RETURN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public List<VarDeclarationContext> varDeclaration() {
			return getRuleContexts(VarDeclarationContext.class);
		}
		public VarDeclarationContext varDeclaration(int i) {
			return getRuleContext(VarDeclarationContext.class,i);
		}
		public LetExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterLetExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitLetExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitLetExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class PrimaryExprContext extends ExpressionContext {
		public PrimaryContext primary() {
			return getRuleContext(PrimaryContext.class,0);
		}
		public PrimaryExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterPrimaryExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitPrimaryExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitPrimaryExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class InExprContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode IN() { return getToken(RaxGrammarParser.IN, 0); }
		public TableConstructorContext tableConstructor() {
			return getRuleContext(TableConstructorContext.class,0);
		}
		public InExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterInExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitInExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitInExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class UnaryExprContext extends ExpressionContext {
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode PLUS() { return getToken(RaxGrammarParser.PLUS, 0); }
		public TerminalNode MINUS() { return getToken(RaxGrammarParser.MINUS, 0); }
		public UnaryExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterUnaryExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitUnaryExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitUnaryExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class AddSubExprContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode PLUS() { return getToken(RaxGrammarParser.PLUS, 0); }
		public TerminalNode MINUS() { return getToken(RaxGrammarParser.MINUS, 0); }
		public AddSubExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterAddSubExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitAddSubExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitAddSubExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LogicalExprContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode AND() { return getToken(RaxGrammarParser.AND, 0); }
		public TerminalNode OR() { return getToken(RaxGrammarParser.OR, 0); }
		public LogicalExprContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterLogicalExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitLogicalExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitLogicalExpr(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ExpressionContext expression() throws RecognitionException {
		return expression(0);
	}

	private ExpressionContext expression(int _p) throws RecognitionException {
		ParserRuleContext _parentctx = _ctx;
		int _parentState = getState();
		ExpressionContext _localctx = new ExpressionContext(_ctx, _parentState);
		ExpressionContext _prevctx = _localctx;
		int _startState = 4;
		enterRecursionRule(_localctx, 4, RULE_expression, _p);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(34);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case VAR:
				{
				_localctx = new LetExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(24); 
				_errHandler.sync(this);
				_la = _input.LA(1);
				do {
					{
					{
					setState(23);
					varDeclaration();
					}
					}
					setState(26); 
					_errHandler.sync(this);
					_la = _input.LA(1);
				} while ( _la==VAR );
				setState(28);
				match(RETURN);
				setState(29);
				expression(10);
				}
				break;
			case NOT:
			case LPAREN:
			case LBRACE:
			case COLUMN_REF:
			case QUOTED_TABLE:
			case ID:
			case NUMBER:
			case STRING_LIT:
				{
				_localctx = new PrimaryExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(31);
				primary();
				}
				break;
			case PLUS:
			case MINUS:
				{
				_localctx = new UnaryExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(32);
				_la = _input.LA(1);
				if ( !(_la==PLUS || _la==MINUS) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				setState(33);
				expression(8);
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			_ctx.stop = _input.LT(-1);
			setState(62);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,4,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(60);
					_errHandler.sync(this);
					switch ( getInterpreter().adaptivePredict(_input,3,_ctx) ) {
					case 1:
						{
						_localctx = new PowerExprContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(36);
						if (!(precpred(_ctx, 7))) throw new FailedPredicateException(this, "precpred(_ctx, 7)");
						setState(37);
						match(CARET);
						setState(38);
						expression(8);
						}
						break;
					case 2:
						{
						_localctx = new MultDivExprContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(39);
						if (!(precpred(_ctx, 6))) throw new FailedPredicateException(this, "precpred(_ctx, 6)");
						setState(40);
						_la = _input.LA(1);
						if ( !(_la==STAR || _la==SLASH) ) {
						_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(41);
						expression(7);
						}
						break;
					case 3:
						{
						_localctx = new AddSubExprContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(42);
						if (!(precpred(_ctx, 5))) throw new FailedPredicateException(this, "precpred(_ctx, 5)");
						setState(43);
						_la = _input.LA(1);
						if ( !(_la==PLUS || _la==MINUS) ) {
						_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(44);
						expression(6);
						}
						break;
					case 4:
						{
						_localctx = new ConcatExprContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(45);
						if (!(precpred(_ctx, 4))) throw new FailedPredicateException(this, "precpred(_ctx, 4)");
						setState(46);
						match(CONCAT);
						setState(47);
						expression(5);
						}
						break;
					case 5:
						{
						_localctx = new ComparisonExprContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(48);
						if (!(precpred(_ctx, 3))) throw new FailedPredicateException(this, "precpred(_ctx, 3)");
						setState(49);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 8323072L) != 0)) ) {
						_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(50);
						expression(4);
						}
						break;
					case 6:
						{
						_localctx = new LogicalExprContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(51);
						if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
						setState(52);
						_la = _input.LA(1);
						if ( !(_la==AND || _la==OR) ) {
						_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(53);
						expression(2);
						}
						break;
					case 7:
						{
						_localctx = new InExprContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(54);
						if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
						setState(55);
						match(IN);
						setState(58);
						_errHandler.sync(this);
						switch ( getInterpreter().adaptivePredict(_input,2,_ctx) ) {
						case 1:
							{
							setState(56);
							tableConstructor();
							}
							break;
						case 2:
							{
							setState(57);
							expression(0);
							}
							break;
						}
						}
						break;
					}
					} 
				}
				setState(64);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,4,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			unrollRecursionContexts(_parentctx);
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PrimaryContext extends ParserRuleContext {
		public PrimaryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_primary; }
	 
		public PrimaryContext() { }
		public void copyFrom(PrimaryContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ColumnOrMeasureContext extends PrimaryContext {
		public TerminalNode COLUMN_REF() { return getToken(RaxGrammarParser.COLUMN_REF, 0); }
		public TableRefContext tableRef() {
			return getRuleContext(TableRefContext.class,0);
		}
		public ColumnOrMeasureContext(PrimaryContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterColumnOrMeasure(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitColumnOrMeasure(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitColumnOrMeasure(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LiteralStringContext extends PrimaryContext {
		public TerminalNode STRING_LIT() { return getToken(RaxGrammarParser.STRING_LIT, 0); }
		public LiteralStringContext(PrimaryContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterLiteralString(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitLiteralString(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitLiteralString(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class VariableReferenceContext extends PrimaryContext {
		public TerminalNode ID() { return getToken(RaxGrammarParser.ID, 0); }
		public VariableReferenceContext(PrimaryContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterVariableReference(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitVariableReference(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitVariableReference(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class FunctionCallContext extends PrimaryContext {
		public TerminalNode LPAREN() { return getToken(RaxGrammarParser.LPAREN, 0); }
		public TerminalNode RPAREN() { return getToken(RaxGrammarParser.RPAREN, 0); }
		public TerminalNode ID() { return getToken(RaxGrammarParser.ID, 0); }
		public TerminalNode NOT() { return getToken(RaxGrammarParser.NOT, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<TerminalNode> ARG_SEP() { return getTokens(RaxGrammarParser.ARG_SEP); }
		public TerminalNode ARG_SEP(int i) {
			return getToken(RaxGrammarParser.ARG_SEP, i);
		}
		public FunctionCallContext(PrimaryContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterFunctionCall(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitFunctionCall(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitFunctionCall(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class QuotedTableReferenceContext extends PrimaryContext {
		public TerminalNode QUOTED_TABLE() { return getToken(RaxGrammarParser.QUOTED_TABLE, 0); }
		public QuotedTableReferenceContext(PrimaryContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterQuotedTableReference(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitQuotedTableReference(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitQuotedTableReference(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class TableLiteralContext extends PrimaryContext {
		public TableConstructorContext tableConstructor() {
			return getRuleContext(TableConstructorContext.class,0);
		}
		public TableLiteralContext(PrimaryContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterTableLiteral(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitTableLiteral(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitTableLiteral(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class GroupedExprContext extends PrimaryContext {
		public TerminalNode LPAREN() { return getToken(RaxGrammarParser.LPAREN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(RaxGrammarParser.RPAREN, 0); }
		public GroupedExprContext(PrimaryContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterGroupedExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitGroupedExpr(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitGroupedExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LiteralNumberContext extends PrimaryContext {
		public TerminalNode NUMBER() { return getToken(RaxGrammarParser.NUMBER, 0); }
		public LiteralNumberContext(PrimaryContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterLiteralNumber(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitLiteralNumber(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitLiteralNumber(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PrimaryContext primary() throws RecognitionException {
		PrimaryContext _localctx = new PrimaryContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_primary);
		int _la;
		try {
			setState(91);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,8,_ctx) ) {
			case 1:
				_localctx = new LiteralNumberContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(65);
				match(NUMBER);
				}
				break;
			case 2:
				_localctx = new LiteralStringContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(66);
				match(STRING_LIT);
				}
				break;
			case 3:
				_localctx = new ColumnOrMeasureContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(68);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==QUOTED_TABLE || _la==ID) {
					{
					setState(67);
					tableRef();
					}
				}

				setState(70);
				match(COLUMN_REF);
				}
				break;
			case 4:
				_localctx = new FunctionCallContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(71);
				_la = _input.LA(1);
				if ( !(_la==NOT || _la==ID) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				setState(72);
				match(LPAREN);
				setState(81);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if ((((_la) & ~0x3f) == 0 && ((1L << _la) & 1040190642L) != 0)) {
					{
					setState(73);
					expression(0);
					setState(78);
					_errHandler.sync(this);
					_la = _input.LA(1);
					while (_la==ARG_SEP) {
						{
						{
						setState(74);
						match(ARG_SEP);
						setState(75);
						expression(0);
						}
						}
						setState(80);
						_errHandler.sync(this);
						_la = _input.LA(1);
					}
					}
				}

				setState(83);
				match(RPAREN);
				}
				break;
			case 5:
				_localctx = new VariableReferenceContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(84);
				match(ID);
				}
				break;
			case 6:
				_localctx = new QuotedTableReferenceContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(85);
				match(QUOTED_TABLE);
				}
				break;
			case 7:
				_localctx = new GroupedExprContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(86);
				match(LPAREN);
				setState(87);
				expression(0);
				setState(88);
				match(RPAREN);
				}
				break;
			case 8:
				_localctx = new TableLiteralContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(90);
				tableConstructor();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class TableRefContext extends ParserRuleContext {
		public TerminalNode ID() { return getToken(RaxGrammarParser.ID, 0); }
		public TerminalNode QUOTED_TABLE() { return getToken(RaxGrammarParser.QUOTED_TABLE, 0); }
		public TableRefContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_tableRef; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterTableRef(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitTableRef(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitTableRef(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TableRefContext tableRef() throws RecognitionException {
		TableRefContext _localctx = new TableRefContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_tableRef);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(93);
			_la = _input.LA(1);
			if ( !(_la==QUOTED_TABLE || _la==ID) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class TableConstructorContext extends ParserRuleContext {
		public TerminalNode LBRACE() { return getToken(RaxGrammarParser.LBRACE, 0); }
		public TerminalNode RBRACE() { return getToken(RaxGrammarParser.RBRACE, 0); }
		public List<TableRowContext> tableRow() {
			return getRuleContexts(TableRowContext.class);
		}
		public TableRowContext tableRow(int i) {
			return getRuleContext(TableRowContext.class,i);
		}
		public List<TerminalNode> ARG_SEP() { return getTokens(RaxGrammarParser.ARG_SEP); }
		public TerminalNode ARG_SEP(int i) {
			return getToken(RaxGrammarParser.ARG_SEP, i);
		}
		public TableConstructorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_tableConstructor; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterTableConstructor(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitTableConstructor(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitTableConstructor(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TableConstructorContext tableConstructor() throws RecognitionException {
		TableConstructorContext _localctx = new TableConstructorContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_tableConstructor);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(95);
			match(LBRACE);
			setState(104);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if ((((_la) & ~0x3f) == 0 && ((1L << _la) & 1040190642L) != 0)) {
				{
				setState(96);
				tableRow();
				setState(101);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==ARG_SEP) {
					{
					{
					setState(97);
					match(ARG_SEP);
					setState(98);
					tableRow();
					}
					}
					setState(103);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				}
			}

			setState(106);
			match(RBRACE);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class TableRowContext extends ParserRuleContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode LPAREN() { return getToken(RaxGrammarParser.LPAREN, 0); }
		public TerminalNode RPAREN() { return getToken(RaxGrammarParser.RPAREN, 0); }
		public List<TerminalNode> ARG_SEP() { return getTokens(RaxGrammarParser.ARG_SEP); }
		public TerminalNode ARG_SEP(int i) {
			return getToken(RaxGrammarParser.ARG_SEP, i);
		}
		public TableRowContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_tableRow; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).enterTableRow(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof RaxGrammarListener ) ((RaxGrammarListener)listener).exitTableRow(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof RaxGrammarVisitor ) return ((RaxGrammarVisitor<? extends T>)visitor).visitTableRow(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TableRowContext tableRow() throws RecognitionException {
		TableRowContext _localctx = new TableRowContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_tableRow);
		int _la;
		try {
			setState(120);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(108);
				expression(0);
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(109);
				match(LPAREN);
				setState(110);
				expression(0);
				setState(115);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==ARG_SEP) {
					{
					{
					setState(111);
					match(ARG_SEP);
					setState(112);
					expression(0);
					}
					}
					setState(117);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				setState(118);
				match(RPAREN);
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public boolean sempred(RuleContext _localctx, int ruleIndex, int predIndex) {
		switch (ruleIndex) {
		case 2:
			return expression_sempred((ExpressionContext)_localctx, predIndex);
		}
		return true;
	}
	private boolean expression_sempred(ExpressionContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0:
			return precpred(_ctx, 7);
		case 1:
			return precpred(_ctx, 6);
		case 2:
			return precpred(_ctx, 5);
		case 3:
			return precpred(_ctx, 4);
		case 4:
			return precpred(_ctx, 3);
		case 5:
			return precpred(_ctx, 1);
		case 6:
			return precpred(_ctx, 2);
		}
		return true;
	}

	public static final String _serializedATN =
		"\u0004\u0001 {\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002\u0002"+
		"\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002\u0005"+
		"\u0007\u0005\u0002\u0006\u0007\u0006\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0002"+
		"\u0001\u0002\u0004\u0002\u0019\b\u0002\u000b\u0002\f\u0002\u001a\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003"+
		"\u0002#\b\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003"+
		"\u0002;\b\u0002\u0005\u0002=\b\u0002\n\u0002\f\u0002@\t\u0002\u0001\u0003"+
		"\u0001\u0003\u0001\u0003\u0003\u0003E\b\u0003\u0001\u0003\u0001\u0003"+
		"\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003\u0005\u0003M\b\u0003"+
		"\n\u0003\f\u0003P\t\u0003\u0003\u0003R\b\u0003\u0001\u0003\u0001\u0003"+
		"\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003"+
		"\u0003\u0003\\\b\u0003\u0001\u0004\u0001\u0004\u0001\u0005\u0001\u0005"+
		"\u0001\u0005\u0001\u0005\u0005\u0005d\b\u0005\n\u0005\f\u0005g\t\u0005"+
		"\u0003\u0005i\b\u0005\u0001\u0005\u0001\u0005\u0001\u0006\u0001\u0006"+
		"\u0001\u0006\u0001\u0006\u0001\u0006\u0005\u0006r\b\u0006\n\u0006\f\u0006"+
		"u\t\u0006\u0001\u0006\u0001\u0006\u0003\u0006y\b\u0006\u0001\u0006\u0000"+
		"\u0001\u0004\u0007\u0000\u0002\u0004\u0006\b\n\f\u0000\u0006\u0001\u0000"+
		"\n\u000b\u0001\u0000\f\r\u0001\u0000\u0010\u0016\u0001\u0000\u0017\u0018"+
		"\u0002\u0000\u0004\u0004\u001b\u001b\u0001\u0000\u001a\u001b\u008c\u0000"+
		"\u000e\u0001\u0000\u0000\u0000\u0002\u0011\u0001\u0000\u0000\u0000\u0004"+
		"\"\u0001\u0000\u0000\u0000\u0006[\u0001\u0000\u0000\u0000\b]\u0001\u0000"+
		"\u0000\u0000\n_\u0001\u0000\u0000\u0000\fx\u0001\u0000\u0000\u0000\u000e"+
		"\u000f\u0003\u0004\u0002\u0000\u000f\u0010\u0005\u0000\u0000\u0001\u0010"+
		"\u0001\u0001\u0000\u0000\u0000\u0011\u0012\u0005\u0001\u0000\u0000\u0012"+
		"\u0013\u0005\u001b\u0000\u0000\u0013\u0014\u0005\u0011\u0000\u0000\u0014"+
		"\u0015\u0003\u0004\u0002\u0000\u0015\u0003\u0001\u0000\u0000\u0000\u0016"+
		"\u0018\u0006\u0002\uffff\uffff\u0000\u0017\u0019\u0003\u0002\u0001\u0000"+
		"\u0018\u0017\u0001\u0000\u0000\u0000\u0019\u001a\u0001\u0000\u0000\u0000"+
		"\u001a\u0018\u0001\u0000\u0000\u0000\u001a\u001b\u0001\u0000\u0000\u0000"+
		"\u001b\u001c\u0001\u0000\u0000\u0000\u001c\u001d\u0005\u0002\u0000\u0000"+
		"\u001d\u001e\u0003\u0004\u0002\n\u001e#\u0001\u0000\u0000\u0000\u001f"+
		"#\u0003\u0006\u0003\u0000 !\u0007\u0000\u0000\u0000!#\u0003\u0004\u0002"+
		"\b\"\u0016\u0001\u0000\u0000\u0000\"\u001f\u0001\u0000\u0000\u0000\" "+
		"\u0001\u0000\u0000\u0000#>\u0001\u0000\u0000\u0000$%\n\u0007\u0000\u0000"+
		"%&\u0005\u000e\u0000\u0000&=\u0003\u0004\u0002\b\'(\n\u0006\u0000\u0000"+
		"()\u0007\u0001\u0000\u0000)=\u0003\u0004\u0002\u0007*+\n\u0005\u0000\u0000"+
		"+,\u0007\u0000\u0000\u0000,=\u0003\u0004\u0002\u0006-.\n\u0004\u0000\u0000"+
		"./\u0005\u000f\u0000\u0000/=\u0003\u0004\u0002\u000501\n\u0003\u0000\u0000"+
		"12\u0007\u0002\u0000\u00002=\u0003\u0004\u0002\u000434\n\u0001\u0000\u0000"+
		"45\u0007\u0003\u0000\u00005=\u0003\u0004\u0002\u000267\n\u0002\u0000\u0000"+
		"7:\u0005\u0003\u0000\u00008;\u0003\n\u0005\u00009;\u0003\u0004\u0002\u0000"+
		":8\u0001\u0000\u0000\u0000:9\u0001\u0000\u0000\u0000;=\u0001\u0000\u0000"+
		"\u0000<$\u0001\u0000\u0000\u0000<\'\u0001\u0000\u0000\u0000<*\u0001\u0000"+
		"\u0000\u0000<-\u0001\u0000\u0000\u0000<0\u0001\u0000\u0000\u0000<3\u0001"+
		"\u0000\u0000\u0000<6\u0001\u0000\u0000\u0000=@\u0001\u0000\u0000\u0000"+
		"><\u0001\u0000\u0000\u0000>?\u0001\u0000\u0000\u0000?\u0005\u0001\u0000"+
		"\u0000\u0000@>\u0001\u0000\u0000\u0000A\\\u0005\u001c\u0000\u0000B\\\u0005"+
		"\u001d\u0000\u0000CE\u0003\b\u0004\u0000DC\u0001\u0000\u0000\u0000DE\u0001"+
		"\u0000\u0000\u0000EF\u0001\u0000\u0000\u0000F\\\u0005\u0019\u0000\u0000"+
		"GH\u0007\u0004\u0000\u0000HQ\u0005\u0005\u0000\u0000IN\u0003\u0004\u0002"+
		"\u0000JK\u0005\t\u0000\u0000KM\u0003\u0004\u0002\u0000LJ\u0001\u0000\u0000"+
		"\u0000MP\u0001\u0000\u0000\u0000NL\u0001\u0000\u0000\u0000NO\u0001\u0000"+
		"\u0000\u0000OR\u0001\u0000\u0000\u0000PN\u0001\u0000\u0000\u0000QI\u0001"+
		"\u0000\u0000\u0000QR\u0001\u0000\u0000\u0000RS\u0001\u0000\u0000\u0000"+
		"S\\\u0005\u0006\u0000\u0000T\\\u0005\u001b\u0000\u0000U\\\u0005\u001a"+
		"\u0000\u0000VW\u0005\u0005\u0000\u0000WX\u0003\u0004\u0002\u0000XY\u0005"+
		"\u0006\u0000\u0000Y\\\u0001\u0000\u0000\u0000Z\\\u0003\n\u0005\u0000["+
		"A\u0001\u0000\u0000\u0000[B\u0001\u0000\u0000\u0000[D\u0001\u0000\u0000"+
		"\u0000[G\u0001\u0000\u0000\u0000[T\u0001\u0000\u0000\u0000[U\u0001\u0000"+
		"\u0000\u0000[V\u0001\u0000\u0000\u0000[Z\u0001\u0000\u0000\u0000\\\u0007"+
		"\u0001\u0000\u0000\u0000]^\u0007\u0005\u0000\u0000^\t\u0001\u0000\u0000"+
		"\u0000_h\u0005\u0007\u0000\u0000`e\u0003\f\u0006\u0000ab\u0005\t\u0000"+
		"\u0000bd\u0003\f\u0006\u0000ca\u0001\u0000\u0000\u0000dg\u0001\u0000\u0000"+
		"\u0000ec\u0001\u0000\u0000\u0000ef\u0001\u0000\u0000\u0000fi\u0001\u0000"+
		"\u0000\u0000ge\u0001\u0000\u0000\u0000h`\u0001\u0000\u0000\u0000hi\u0001"+
		"\u0000\u0000\u0000ij\u0001\u0000\u0000\u0000jk\u0005\b\u0000\u0000k\u000b"+
		"\u0001\u0000\u0000\u0000ly\u0003\u0004\u0002\u0000mn\u0005\u0005\u0000"+
		"\u0000ns\u0003\u0004\u0002\u0000op\u0005\t\u0000\u0000pr\u0003\u0004\u0002"+
		"\u0000qo\u0001\u0000\u0000\u0000ru\u0001\u0000\u0000\u0000sq\u0001\u0000"+
		"\u0000\u0000st\u0001\u0000\u0000\u0000tv\u0001\u0000\u0000\u0000us\u0001"+
		"\u0000\u0000\u0000vw\u0005\u0006\u0000\u0000wy\u0001\u0000\u0000\u0000"+
		"xl\u0001\u0000\u0000\u0000xm\u0001\u0000\u0000\u0000y\r\u0001\u0000\u0000"+
		"\u0000\r\u001a\":<>DNQ[ehsx";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}