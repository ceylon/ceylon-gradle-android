
/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

ruleset {
  // rulesets/braces.xml
  ElseBlockBraces
  ForStatementBraces
  IfStatementBraces
  WhileStatementBraces

  // rulesets/imports.xml
  DuplicateImport
  ImportFromSamePackage
  UnnecessaryGroovyImport
  UnusedImport

  // rulesets/naming.xml
  AbstractClassName
  ClassName {
    regex = '^[A-Z][\\$a-zA-Z0-9]*(?<!Test)'
  }
  ClassNameSameAsFilename
  FieldName {
    regex = '^_?[a-z][a-zA-Z0-9]*$'
    finalRegex = '^_?[a-z][a-zA-Z0-9]*$'
    staticFinalRegex = '^logger$|^[A-Z][A-Z_0-9]*$|^serialVersionUID$'
  }
  InterfaceName
  MethodName {
    regex = '^[a-z][\\$_a-zA-Z0-9]*$|^.*\\s.*$'
  }
  ObjectOverrideMisspelledMethodName
  PackageName
  ParameterName
  PropertyName
  VariableName {
    finalRegex = '^[a-z][a-zA-Z0-9]*$'
  }

  // rulesets/unnecessary.xml
  AddEmptyString
  ConsecutiveLiteralAppends
  ConsecutiveStringConcatenation
  UnnecessaryBigDecimalInstantiation
  UnnecessaryBigIntegerInstantiation
  UnnecessaryBooleanInstantiation
  UnnecessaryCallToSubstring
  UnnecessaryCatchBlock
  UnnecessaryCollectionCall
  UnnecessaryConstructor
  UnnecessaryDefInFieldDeclaration
  UnnecessaryDefInVariableDeclaration
  UnnecessaryDotClass
  UnnecessaryDoubleInstantiation
  UnnecessaryElseStatement
  UnnecessaryFinalOnPrivateMethod
  UnnecessaryFloatInstantiation
  UnnecessaryIfStatement
  UnnecessaryInstanceOfCheck
  UnnecessaryInstantiationToGetClass
  UnnecessaryIntegerInstantiation
  UnnecessaryLongInstantiation
  UnnecessaryModOne
  UnnecessaryNullCheck
  UnnecessaryNullCheckBeforeInstanceOf
  UnnecessaryObjectReferences
  UnnecessaryOverridingMethod
  UnnecessaryParenthesesForMethodCallWithClosure
  UnnecessarySemicolon
  UnnecessaryStringInstantiation
  UnnecessaryTernaryExpression
  UnnecessaryTransientModifier

  // rulesets/unused.xml
  UnusedArray
  UnusedPrivateField
  UnusedPrivateMethod
  UnusedPrivateMethodParameter
  UnusedVariable
}