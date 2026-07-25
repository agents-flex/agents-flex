/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.store.condition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a deliberately small SQL-like expression language into a {@link Condition} tree.
 * Supported predicates are comparisons, IN/NOT IN, BETWEEN/NOT BETWEEN, and IS NULL/IS NOT NULL.
 * AND, OR, NOT, and parentheses are supported with standard SQL precedence. String values must be
 * quoted; field names may be unquoted paths or backtick-quoted names.
 */
public final class ConditionExpressionParser {

    private ConditionExpressionParser() {
    }

    public static Condition parse(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("Condition expression cannot be null");
        }
        return new Parser(expression).parse();
    }

    private static final class Parser {
        private final Lexer lexer;
        private Token current;

        private Parser(String expression) {
            this.lexer = new Lexer(expression);
            this.current = lexer.next();
        }

        private Condition parse() {
            if (current.type == TokenType.EOF) {
                throw error(current, "Condition expression cannot be blank");
            }
            Condition condition = parseOr();
            expect(TokenType.EOF, "Unexpected token '" + current.text + "'");
            return condition;
        }

        private Condition parseOr() {
            Condition left = parseAnd();
            while (match(TokenType.OR)) {
                left.connect(parseAnd(), Connector.OR);
            }
            return left;
        }

        private Condition parseAnd() {
            Condition left = parseUnary();
            while (match(TokenType.AND)) {
                left.connect(parseUnary(), Connector.AND);
            }
            return left;
        }

        private Condition parseUnary() {
            if (match(TokenType.NOT)) {
                return new Not(parseUnary());
            }
            if (match(TokenType.LEFT_PAREN)) {
                Condition child = parseOr();
                expect(TokenType.RIGHT_PAREN, "Expected ')' to close condition group");
                return new Group(child);
            }
            return parsePredicate();
        }

        private Condition parsePredicate() {
            Token field = expect(TokenType.IDENTIFIER, "Expected a field name");
            Key key = new Key(field.value);

            if (match(TokenType.EQ)) {
                return comparison(ConditionType.EQ, key, parseLiteral());
            }
            if (match(TokenType.NE)) {
                return comparison(ConditionType.NE, key, parseLiteral());
            }
            if (match(TokenType.GT)) {
                return orderedComparison(ConditionType.GT, key);
            }
            if (match(TokenType.GE)) {
                return orderedComparison(ConditionType.GE, key);
            }
            if (match(TokenType.LT)) {
                return orderedComparison(ConditionType.LT, key);
            }
            if (match(TokenType.LE)) {
                return orderedComparison(ConditionType.LE, key);
            }
            if (match(TokenType.IN)) {
                return in(key, false);
            }
            if (match(TokenType.BETWEEN)) {
                return between(key, false);
            }
            if (match(TokenType.IS)) {
                boolean negated = match(TokenType.NOT);
                expect(TokenType.NULL, "Expected NULL after IS or IS NOT");
                return comparison(negated ? ConditionType.NE : ConditionType.EQ, key, null);
            }
            if (match(TokenType.NOT)) {
                if (match(TokenType.IN)) {
                    return in(key, true);
                }
                if (match(TokenType.BETWEEN)) {
                    return between(key, true);
                }
                throw error(current, "Expected IN or BETWEEN after NOT");
            }
            throw error(current, "Expected a comparison operator after field '" + field.value + "'");
        }

        private Condition orderedComparison(ConditionType type, Key key) {
            Token literal = current;
            Object value = parseLiteral();
            if (value == null) {
                throw error(literal, "NULL is only supported with =, !=, IS NULL, or IS NOT NULL");
            }
            return comparison(type, key, value);
        }

        private Condition in(Key key, boolean negated) {
            expect(TokenType.LEFT_PAREN, "Expected '(' after IN");
            List<Object> values = new ArrayList<>();
            if (current.type == TokenType.RIGHT_PAREN) {
                throw error(current, "IN requires at least one value");
            }
            do {
                Token literal = current;
                Object value = parseLiteral();
                if (value == null) {
                    throw error(literal, "NULL is not supported inside IN or NOT IN");
                }
                values.add(value);
            } while (match(TokenType.COMMA));
            expect(TokenType.RIGHT_PAREN, "Expected ')' after IN values");
            return new Condition(negated ? ConditionType.NIN : ConditionType.IN,
                key, new Value(values.toArray()));
        }

        private Condition between(Key key, boolean negated) {
            Token startToken = current;
            Object start = parseLiteral();
            if (start == null) {
                throw error(startToken, "BETWEEN bounds cannot be NULL");
            }
            expect(TokenType.AND, "Expected AND between BETWEEN bounds");
            Token endToken = current;
            Object end = parseLiteral();
            if (end == null) {
                throw error(endToken, "BETWEEN bounds cannot be NULL");
            }
            Condition between = new Condition(ConditionType.BETWEEN, key, new Value(start, end));
            return negated ? new Not(between) : between;
        }

        private Condition comparison(ConditionType type, Key key, Object value) {
            return new Condition(type, key, new Value(value));
        }

        private Object parseLiteral() {
            Token token = current;
            switch (token.type) {
                case STRING:
                case BOOLEAN:
                    advance();
                    return token.value;
                case NUMBER:
                    advance();
                    return parseNumber(token);
                case NULL:
                    advance();
                    return null;
                default:
                    throw error(token, "Expected a quoted string, number, boolean, or NULL");
            }
        }

        private Number parseNumber(Token token) {
            try {
                if (token.text.indexOf('.') >= 0 || token.text.indexOf('e') >= 0
                    || token.text.indexOf('E') >= 0) {
                    return new BigDecimal(token.text);
                }
                return Long.valueOf(token.text);
            } catch (NumberFormatException e) {
                throw error(token, "Invalid or out-of-range number '" + token.text + "'");
            }
        }

        private boolean match(TokenType type) {
            if (current.type != type) {
                return false;
            }
            advance();
            return true;
        }

        private Token expect(TokenType type, String message) {
            if (current.type != type) {
                throw error(current, message);
            }
            Token token = current;
            advance();
            return token;
        }

        private void advance() {
            current = lexer.next();
        }

        private IllegalArgumentException error(Token token, String message) {
            return new IllegalArgumentException("Invalid condition expression at position "
                + token.position + ": " + message);
        }
    }

    private static final class Lexer {
        private final String input;
        private int index;

        private Lexer(String input) {
            this.input = input;
        }

        private Token next() {
            skipWhitespace();
            if (index >= input.length()) {
                return new Token(TokenType.EOF, "", null, index);
            }
            int start = index;
            char current = input.charAt(index++);
            switch (current) {
                case '(':
                    return token(TokenType.LEFT_PAREN, start);
                case ')':
                    return token(TokenType.RIGHT_PAREN, start);
                case ',':
                    return token(TokenType.COMMA, start);
                case '=':
                    if (peek('=')) {
                        index++;
                    }
                    return token(TokenType.EQ, start);
                case '!':
                    require('=', start, "Expected '=' after '!'");
                    return token(TokenType.NE, start);
                case '>':
                    if (peek('=')) {
                        index++;
                        return token(TokenType.GE, start);
                    }
                    return token(TokenType.GT, start);
                case '<':
                    if (peek('=')) {
                        index++;
                        return token(TokenType.LE, start);
                    }
                    if (peek('>')) {
                        index++;
                        return token(TokenType.NE, start);
                    }
                    return token(TokenType.LT, start);
                case '\'':
                case '"':
                    return string(current, start);
                case '`':
                    return quotedIdentifier(start);
                default:
                    if (isNumberStart(current)) {
                        return number(start);
                    }
                    if (isIdentifierStart(current)) {
                        return identifier(start);
                    }
                    throw lexerError(start, "Unexpected character '" + current + "'");
            }
        }

        private Token string(char quote, int start) {
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == quote) {
                    if (peek(quote)) {
                        index++;
                        value.append(quote);
                        continue;
                    }
                    return new Token(TokenType.STRING, input.substring(start, index), value.toString(), start);
                }
                if (current == '\\') {
                    if (index >= input.length()) {
                        throw lexerError(index - 1, "Unterminated escape sequence");
                    }
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case 'n': value.append('\n'); break;
                        case 'r': value.append('\r'); break;
                        case 't': value.append('\t'); break;
                        case 'b': value.append('\b'); break;
                        case 'f': value.append('\f'); break;
                        case '\\': value.append('\\'); break;
                        case '\'': value.append('\''); break;
                        case '"': value.append('"'); break;
                        default: throw lexerError(index - 1, "Unsupported escape sequence '\\" + escaped + "'");
                    }
                } else {
                    value.append(current);
                }
            }
            throw lexerError(start, "Unterminated string literal");
        }

        private Token quotedIdentifier(int start) {
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '`') {
                    if (peek('`')) {
                        index++;
                        value.append('`');
                        continue;
                    }
                    if (value.length() == 0) {
                        throw lexerError(start, "Field name cannot be empty");
                    }
                    return new Token(TokenType.IDENTIFIER, input.substring(start, index), value.toString(), start);
                }
                value.append(current);
            }
            throw lexerError(start, "Unterminated quoted field name");
        }

        private Token number(int start) {
            if ((input.charAt(start) == '+' || input.charAt(start) == '-') && index >= input.length()) {
                throw lexerError(start, "Expected a digit after '" + input.charAt(start) + "'");
            }
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (peek('.')) {
                index++;
                int decimalStart = index;
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
                if (decimalStart == index) {
                    throw lexerError(index, "Expected digits after decimal point");
                }
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                int exponentStart = index;
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
                if (exponentStart == index) {
                    throw lexerError(index, "Expected exponent digits");
                }
            }
            return new Token(TokenType.NUMBER, input.substring(start, index), null, start);
        }

        private Token identifier(int start) {
            while (index < input.length() && isIdentifierPart(input.charAt(index))) {
                index++;
            }
            String text = input.substring(start, index);
            String keyword = text.toUpperCase(java.util.Locale.ROOT);
            switch (keyword) {
                case "AND": return new Token(TokenType.AND, text, null, start);
                case "OR": return new Token(TokenType.OR, text, null, start);
                case "NOT": return new Token(TokenType.NOT, text, null, start);
                case "IN": return new Token(TokenType.IN, text, null, start);
                case "BETWEEN": return new Token(TokenType.BETWEEN, text, null, start);
                case "IS": return new Token(TokenType.IS, text, null, start);
                case "NULL": return new Token(TokenType.NULL, text, null, start);
                case "TRUE": return new Token(TokenType.BOOLEAN, text, Boolean.TRUE, start);
                case "FALSE": return new Token(TokenType.BOOLEAN, text, Boolean.FALSE, start);
                default: return new Token(TokenType.IDENTIFIER, text, text, start);
            }
        }

        private boolean isNumberStart(char current) {
            if (Character.isDigit(current)) {
                return true;
            }
            return (current == '+' || current == '-')
                && index < input.length() && Character.isDigit(input.charAt(index));
        }

        private boolean isIdentifierStart(char value) {
            return Character.isLetter(value) || value == '_' || value == '$';
        }

        private boolean isIdentifierPart(char value) {
            return Character.isLetterOrDigit(value) || value == '_' || value == '$'
                || value == '.' || value == '-';
        }

        private boolean peek(char expected) {
            return index < input.length() && input.charAt(index) == expected;
        }

        private void require(char expected, int position, String message) {
            if (!peek(expected)) {
                throw lexerError(position, message);
            }
            index++;
        }

        private Token token(TokenType type, int start) {
            return new Token(type, input.substring(start, index), null, start);
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException lexerError(int position, String message) {
            return new IllegalArgumentException("Invalid condition expression at position "
                + position + ": " + message);
        }
    }

    private enum TokenType {
        IDENTIFIER,
        STRING,
        NUMBER,
        BOOLEAN,
        NULL,
        AND,
        OR,
        NOT,
        IN,
        BETWEEN,
        IS,
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE,
        LEFT_PAREN,
        RIGHT_PAREN,
        COMMA,
        EOF
    }

    private static final class Token {
        private final TokenType type;
        private final String text;
        private final Object value;
        private final int position;

        private Token(TokenType type, String text, Object value, int position) {
            this.type = type;
            this.text = text;
            this.value = value;
            this.position = position;
        }
    }
}
