/**
 * 
 */
angular.module('mtConfigView', [])
.factory('MonitorTypeService', [ '$resource', function($resource) {
	return $resource('/MonitorType/:id', {id:'@_id'});
} ])

.controller('mtConfigCtrl',
[ 'MonitorTypeService',
  '$scope', 
  function($monitorType, $scope) {
	$scope.monitorTypeList = $monitorType.query();
	$scope.displayInstrument = function(mt){
		if(mt.measuringBy)
			return mt.measuringBy;
		else if(mt.measuredBy)
			return mt.measuredBy + "(停用)";
		else
			return "未使用";
	}
	
}]);
