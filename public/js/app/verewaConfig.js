/**
 *
 */
angular.module('verewaConfigView', [])
.controller('verewaConfigCtrl',
[ 'InstConfigService',
  '$scope',
  function($config, $scope) {
	$scope.param = $config.param;

	$config.summary = function() {
		var desc = "測項:" + $scope.param.monitorType;

		return desc;
	}

	$config.validate=function(){
		//copy back
		$config.param = $scope.param;

		return true;
	}
  } ]);
