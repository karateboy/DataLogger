/**
 * newInstrumentApp
 */

angular.module('newInstrumentApp', []).factory('InstTypeInfoService', ['$resource', function($resource){
	return $resource('/InstrumentTypeInfo/:id');
}]).controller('MainCtrl', ['InstTypeInfoService' ,function($instTypeInfoService) {
	var self = this;
	self.instrumentTypeInfos = $instTypeInfoService.query();	
}]);